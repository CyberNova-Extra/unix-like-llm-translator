package local.qwentranslate;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The terminal session: its text, command history, exported variables and the
 * translate processes it runs, one at a time like a shell (input typed while a
 * command runs waits for it). Outlives activities; used on the main thread only.
 */
final class Session {
    interface Listener {
        /** A command started, ended or was queued. */
        void onStateChanged(boolean running, int queued);

        /** The "exit" command. */
        void onExit();
    }

    /** Words that start a command; any other input is text to translate. */
    private static final List<String> COMMANDS =
            Arrays.asList("translate", "help", "clear", "exit", "env", "export", "unset", "models");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*", Pattern.DOTALL);
    private static final int MAX_HISTORY = 200;
    /** Exit status of a process killed by SIGKILL (128 + 9). */
    private static final int KILLED = 137;

    @SuppressLint("StaticFieldLeak") // holds the application context only
    private static Session instance;

    static Session get(Context context) {
        if (instance == null) instance = new Session(context.getApplicationContext());
        return instance;
    }

    /** Terminal text, shown as-is by the activity's TextView. */
    final SpannableStringBuilder text = new SpannableStringBuilder();
    private final TerminalBuffer term;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final File home;
    private final Map<String, String> exports = new TreeMap<>();
    private final List<String> history = new ArrayList<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private Process process;
    /** Prompt line of the input being run, for the notification. */
    private String running = "";
    private boolean interrupted;
    private final StringBuilder output = new StringBuilder();
    private String lastResult = "";
    private Listener listener;

    /** Input waiting for the running command to finish. */
    private static final class Pending {
        /** What the prompt line shows. */
        final String echo;
        /** A command line to interpret, or null to run translate with {@link #args}. */
        final String line;
        final List<String> args;

        Pending(String echo, String line, List<String> args) {
            this.echo = echo;
            this.line = line;
            this.args = args;
        }
    }

    private Session(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE);
        home = new File(app.getFilesDir(), "home");
        //noinspection ResultOfMethodCallIgnored
        home.mkdirs();
        int[] colors = {
            0, // NORMAL: the TextView's own color
            app.getColor(R.color.term_prompt),
            app.getColor(R.color.term_stderr),
            app.getColor(R.color.term_info),
            app.getColor(R.color.term_error),
        };
        term = new TerminalBuffer(new TerminalBuffer.Screen() {
            @Override
            public int length() {
                return text.length();
            }

            @Override
            public char charAt(int index) {
                return text.charAt(index);
            }

            @Override
            public void replace(int start, int end, CharSequence s, int style) {
                text.replace(start, end, s);
                if (colors[style] != 0 && s.length() > 0) {
                    text.setSpan(new ForegroundColorSpan(colors[style]), start, start + s.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        });
        load();
        Images.cleanUp(app);
        term.write(app.getString(R.string.welcome), TerminalBuffer.INFO);
    }

    void setListener(Listener l) {
        listener = l;
        if (l != null) l.onStateChanged(process != null, queue.size());
    }

    List<String> history() {
        return Collections.unmodifiableList(history);
    }

    /** Standard output of the last command that succeeded. */
    String lastResult() {
        return lastResult;
    }

    File home() {
        return home;
    }

    /** A command runs or waits. */
    boolean busy() {
        return process != null || !queue.isEmpty();
    }

    /** A line typed at the prompt. */
    void submit(String line) {
        String cmd = line.trim();
        if (cmd.isEmpty()) {
            if (process == null) echo("");
            return;
        }
        remember(cmd);
        enqueue(new Pending(cmd, cmd, null));
    }

    /** Translates {@code text} as it is (shared or selected in another app). */
    void translate(String text) {
        String t = text.trim();
        if (!t.isEmpty()) enqueue(new Pending(t, null, Arrays.asList("--", t)));
    }

    /** Translates the image behind {@code uri} (shared, or picked with the IMAGE key). */
    void translateImage(Uri uri) {
        new Thread(() -> {
            try {
                File image = Images.importImage(app, uri);
                main.post(() -> run(Arrays.asList("--image", image.getPath())));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                main.post(() -> error(app.getString(R.string.image_failed, String.valueOf(e.getMessage()))));
            }
        }, "image-import").start();
    }

    /** Runs translate with these arguments, shown as a command line. */
    void run(List<String> args) {
        StringBuilder echo = new StringBuilder("translate");
        for (String a : args) echo.append(' ').append(CommandLine.quote(a));
        String cmd = echo.toString();
        remember(cmd);
        enqueue(new Pending(cmd, null, args));
    }

    /** ^C: stops the running command and drops the queued input. */
    void interrupt() {
        queue.clear();
        if (process != null) {
            interrupted = true;
            term.write("^C", TerminalBuffer.INFO);
            process.destroy();
        }
        stateChanged();
    }

    void clear() {
        // Not clearSpans(): that would also drop the TextView's change watchers
        text.clear();
        term.reset();
    }

    private void enqueue(Pending p) {
        queue.add(p);
        drain();
    }

    /** Starts queued input until a command is running or nothing is left. */
    private void drain() {
        while (process == null && !queue.isEmpty()) {
            Pending p = queue.poll();
            running = p.echo;
            echo(p.echo);
            if (p.line != null) execute(p.line);
            else start(p.args);
        }
        if (process == null) SessionService.stop();
        stateChanged();
    }

    private void stateChanged() {
        if (listener != null) listener.onStateChanged(process != null, queue.size());
    }

    private void echo(String cmd) {
        term.ensureNewline();
        term.write("$ ", TerminalBuffer.PROMPT);
        term.write(cmd + "\n", TerminalBuffer.NORMAL);
    }

    private void execute(String cmd) {
        if (COMMANDS.contains(CommandLine.firstWord(cmd))) {
            List<String> words;
            try {
                words = CommandLine.split(cmd, home.getPath());
            } catch (IllegalArgumentException e) {
                error(e.getMessage());
                return;
            }
            if (command(words.get(0), words.subList(1, words.size()))) return;
        }
        // Plain text: everything after "--" is text, even if it starts with a dash
        start(Arrays.asList("--", cmd));
    }

    /** Runs a command; false when the arguments don't fit it (then it's text). */
    private boolean command(String name, List<String> args) {
        switch (name) {
            case "translate":
                start(new ArrayList<>(args));
                return true;
            case "help":
                if (!args.isEmpty()) return false;
                info(app.getString(R.string.help));
                return true;
            case "clear":
                if (!args.isEmpty()) return false;
                clear();
                return true;
            case "exit":
                if (!args.isEmpty()) return false;
                if (listener != null) listener.onExit();
                return true;
            case "env":
                if (!args.isEmpty()) return false;
                listExports();
                return true;
            case "export":
                if (args.isEmpty()) {
                    listExports();
                    return true;
                }
                for (String a : args) if (!ASSIGNMENT.matcher(a).matches()) return false;
                for (String a : args) {
                    int eq = a.indexOf('=');
                    exports.put(a.substring(0, eq), a.substring(eq + 1));
                }
                saveExports();
                return true;
            case "unset":
                if (args.isEmpty()) return false;
                for (String a : args) if (!NAME.matcher(a).matches()) return false;
                for (String a : args) exports.remove(a);
                saveExports();
                return true;
            case "models":
                if (args.isEmpty()) {
                    listModels();
                    return true;
                }
                if (args.size() < 2 || !args.get(0).equals("rm")) return false;
                removeModels(args.subList(1, args.size()));
                return true;
            default:
                return false;
        }
    }

    private void start(List<String> args) {
        File binary = new File(app.getApplicationInfo().nativeLibraryDir, "libtranslate.so");
        if (!binary.canExecute()) {
            error(app.getString(R.string.binary_missing, binary.getPath()));
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.getPath());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(home);
        Map<String, String> env = pb.environment();
        env.put("HOME", home.getPath());
        env.put("TMPDIR", app.getCacheDir().getPath());
        env.putAll(exports);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            error(app.getString(R.string.start_failed, e.getMessage()));
            return;
        }
        try {
            // No stdin: translate would otherwise wait for input that never comes
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // nothing to close
        }
        process = p;
        interrupted = false;
        output.setLength(0);
        SessionService.start(app, running);
        Thread out = pump(p, p.getInputStream(), TerminalBuffer.NORMAL);
        Thread err = pump(p, p.getErrorStream(), TerminalBuffer.STDERR);
        new Thread(() -> {
            int code;
            try {
                code = p.waitFor();
                // Output read so far is posted before the end of the command
                out.join();
                err.join();
            } catch (InterruptedException e) {
                code = -1;
            }
            int exit = code;
            main.post(() -> finished(p, exit));
        }, "translate-wait").start();
    }

    private Thread pump(Process p, InputStream in, int style) {
        Thread t = new Thread(() -> {
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) {
                    String chunk = new String(buf, 0, n);
                    main.post(() -> {
                        term.write(chunk, style);
                        if (style == TerminalBuffer.NORMAL && p == process) output.append(chunk);
                    });
                }
            } catch (IOException ignored) {
                // the process went away
            }
        }, "translate-output");
        t.start();
        return t;
    }

    private void finished(Process p, int code) {
        if (p != process) return;
        process = null;
        term.ensureNewline();
        if (interrupted) {
            interrupted = false;
        } else if (code == 0) {
            lastResult = output.toString().trim();
        } else if (code == KILLED) {
            error(app.getString(R.string.killed));
        } else {
            error(app.getString(R.string.exit_code, code));
        }
        output.setLength(0);
        drain();
    }

    private void info(String msg) {
        term.ensureNewline();
        term.write(msg.endsWith("\n") ? msg : msg + "\n", TerminalBuffer.INFO);
    }

    void error(String msg) {
        term.ensureNewline();
        term.write(msg + "\n", TerminalBuffer.ERROR);
    }

    /** Where translate keeps its models (it follows XDG_DATA_HOME like on Linux). */
    File modelDir() {
        String data = exports.get("XDG_DATA_HOME");
        File base = data != null && !data.isEmpty()
                ? new File(data)
                : new File(home, ".local/share");
        return new File(base, "qwen-translate");
    }

    @SuppressLint("UsableSpace") // informational: what models can still be downloaded into
    private void listModels() {
        File dir = modelDir();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".gguf") || n.endsWith(".gguf.tmp"));
        StringBuilder s = new StringBuilder();
        if (files == null || files.length == 0) {
            s.append(app.getString(R.string.models_none)).append('\n');
        } else {
            Arrays.sort(files);
            for (File f : files) {
                s.append(String.format(Locale.ROOT, "  %-32s %9s", f.getName(), size(f.length())));
                if (f.getName().endsWith(".tmp")) s.append(app.getString(R.string.models_partial));
                s.append('\n');
            }
        }
        term.write(s, TerminalBuffer.NORMAL);
        info(app.getString(R.string.models_where, dir.getPath(), size(home.getUsableSpace())));
    }

    private void removeModels(List<String> names) {
        File dir = modelDir();
        for (String name : names) {
            File f = new File(dir, name);
            boolean model = name.endsWith(".gguf") || name.endsWith(".gguf.tmp");
            if (name.contains("/") || !model || !f.isFile()) {
                error(app.getString(R.string.models_not_found, name));
            } else if (f.delete()) {
                info(app.getString(R.string.models_removed, name));
            } else {
                error(app.getString(R.string.models_not_removed, name));
            }
        }
    }

    private void listExports() {
        if (exports.isEmpty()) {
            info(app.getString(R.string.env_empty));
            return;
        }
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, String> e : exports.entrySet()) {
            s.append(e.getKey()).append('=').append(CommandLine.quote(e.getValue())).append('\n');
        }
        term.write(s, TerminalBuffer.NORMAL);
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void remember(String cmd) {
        if (history.isEmpty() || !history.get(history.size() - 1).equals(cmd)) history.add(cmd);
        while (history.size() > MAX_HISTORY) history.remove(0);
        prefs.edit().putString("history", new JSONArray(history).toString()).apply();
    }

    private void saveExports() {
        prefs.edit().putString("exports", new JSONObject(exports).toString()).apply();
    }

    private void load() {
        try {
            JSONArray h = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < h.length(); i++) history.add(h.getString(i));
            JSONObject e = new JSONObject(prefs.getString("exports", "{}"));
            for (Iterator<String> it = e.keys(); it.hasNext(); ) {
                String k = it.next();
                exports.put(k, e.getString(k));
            }
        } catch (JSONException ignored) {
            // start afresh
        }
    }
}
