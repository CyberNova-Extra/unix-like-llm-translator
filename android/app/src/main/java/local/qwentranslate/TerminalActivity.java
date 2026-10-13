package local.qwentranslate;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** The terminal: session output above, a prompt line and a row of extra keys below. */
public final class TerminalActivity extends Activity implements Session.Listener {
    private static final float MIN_TEXT_SP = 8;
    private static final float MAX_TEXT_SP = 32;
    private static final int REQUEST_IMAGE = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;

    private Session session;
    private SharedPreferences prefs;
    private ScrollView scroll;
    private TextView screen;
    private TextView prompt;
    private EditText input;
    /** Keep the newest output in view, unless the user scrolled up to read. */
    private boolean follow = true;
    /** Position in the history while browsing it; its size means the line being typed. */
    private int historyIndex;
    private String draft = "";
    private ScaleGestureDetector zoom;
    private float textSp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.terminal);
        session = Session.get(this);
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        scroll = findViewById(R.id.scroll);
        screen = findViewById(R.id.screen);
        prompt = findViewById(R.id.prompt);
        input = findViewById(R.id.input);
        fitInsets(findViewById(R.id.root));

        // The view shows the session's own text, so output appears as it is written
        screen.setEditableFactory(new Editable.Factory() {
            @Override
            public Editable newEditable(CharSequence source) {
                return (Editable) source;
            }
        });
        screen.setText(session.text, TextView.BufferType.EDITABLE);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> follow = atBottom());
        screen.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (follow) scrollToBottom();
        });
        // A tap on the output goes back to typing; a long press still selects text
        screen.setOnClickListener(v -> {
            if (!screen.hasSelection()) focusInput();
        });

        textSp = prefs.getFloat("textSize", 13f);
        applyTextSize();
        zoom = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                textSp = Math.max(MIN_TEXT_SP, Math.min(MAX_TEXT_SP, textSp * d.getScaleFactor()));
                applyTextSize();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                prefs.edit().putFloat("textSize", textSp).apply();
            }
        });

        // A single-line field (Enter sends) that still wraps long text
        input.setHorizontallyScrolling(false);
        input.setMaxLines(4);
        input.setOnEditorActionListener((v, action, event) -> {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                // Hardware Enter: send on key down, swallow the key up
                if (event.getAction() == KeyEvent.ACTION_DOWN) submit();
                return true;
            }
            if (action == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });
        input.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_C && event.isCtrlPressed()
                    && input.getSelectionStart() == input.getSelectionEnd()) {
                session.interrupt();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) return browseHistory(-1);
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) return browseHistory(1);
            return false;
        });
        historyIndex = session.history().size();

        LinearLayout keys = findViewById(R.id.keys);
        addKey(keys, R.string.key_interrupt, () -> session.interrupt());
        addKey(keys, R.string.key_up, () -> browseHistory(-1));
        addKey(keys, R.string.key_down, () -> browseHistory(1));
        addKey(keys, R.string.key_image, this::pickImage);
        addKey(keys, R.string.key_paste, this::paste);
        addKey(keys, R.string.key_copy, this::copyResult);
        addKey(keys, R.string.key_clear, () -> session.clear());
        addKey(keys, R.string.key_help, () -> run("help"));

        input.requestFocus();
        // Not again when recreated: the request was handled already
        if (state == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Text selected in another app ("Qwen Translate" in its menu), or shared text / image. */
    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (text != null) {
                follow = true;
                session.translate(text.toString());
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            String type = intent.getType();
            Uri image = type != null && type.startsWith("image/") ? sharedStream(intent) : null;
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            follow = true;
            if (image != null) session.translateImage(image);
            else if (text != null) session.translate(text.toString());
        }
    }

    @SuppressWarnings("deprecation") // the typed variant needs Android 13
    private static Uri sharedStream(Intent intent) {
        return Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private void pickImage() {
        // The photo picker needs no storage permission; older versions use a document picker
        Intent pick = Build.VERSION.SDK_INT >= 33
                ? new Intent(MediaStore.ACTION_PICK_IMAGES)
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(pick, REQUEST_IMAGE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_image_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation") // the framework Activity has no result API besides this one
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_IMAGE && result == RESULT_OK && data != null && data.getData() != null) {
            follow = true;
            session.translateImage(data.getData());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        session.setListener(this);
    }

    @Override
    protected void onStop() {
        session.setListener(null);
        super.onStop();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        zoom.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onStateChanged(boolean running, int queued) {
        if (running) askForNotifications();
        prompt.setAlpha(running ? 0.4f : 1f);
        if (!running) input.setHint(R.string.input_hint);
        else if (queued == 0) input.setHint(R.string.input_hint_running);
        else input.setHint(getResources().getQuantityString(R.plurals.input_hint_queued, queued, queued));
    }

    /** Android 13+: once, so the notification of a running command can be shown. */
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("askedNotifications", false)) return;
        prefs.edit().putBoolean("askedNotifications", true).apply();
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    public void onExit() {
        finish();
    }

    private void submit() {
        String line = input.getText().toString();
        input.setText("");
        run(line);
    }

    private void run(String line) {
        follow = true;
        session.submit(line);
        historyIndex = session.history().size();
        draft = "";
    }

    private boolean browseHistory(int step) {
        List<String> history = session.history();
        int i = historyIndex + step;
        if (i < 0 || i > history.size()) return true;
        if (historyIndex == history.size()) draft = input.getText().toString();
        historyIndex = i;
        input.setText(i == history.size() ? draft : history.get(i));
        input.setSelection(input.length());
        return true;
    }

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        int start = Math.max(0, Math.min(input.getSelectionStart(), input.getSelectionEnd()));
        int end = Math.max(input.getSelectionStart(), input.getSelectionEnd());
        input.getText().replace(start, Math.max(start, end), text);
    }

    private void copyResult() {
        String result = session.lastResult();
        if (result.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), result));
        // Android 13+ confirms copies itself
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void addKey(LinearLayout row, int label, Runnable action) {
        TextView key = new TextView(this);
        key.setText(label);
        key.setTextColor(getColor(R.color.term_fg));
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        key.setGravity(Gravity.CENTER);
        key.setMinWidth(dp(48));
        key.setMinHeight(dp(44));
        key.setPadding(dp(12), 0, dp(12), 0);
        TypedArray a = obtainStyledAttributes(new int[] {android.R.attr.selectableItemBackground});
        key.setBackground(a.getDrawable(0));
        a.recycle();
        // Keys must not take the focus (and the keyboard) away from the prompt
        key.setFocusable(false);
        key.setOnClickListener(v -> action.run());
        row.addView(key);
    }

    private void focusInput() {
        input.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(input, 0);
    }

    private void applyTextSize() {
        screen.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
    }

    private boolean atBottom() {
        return scroll.getScrollY() + scroll.getHeight() >= screen.getHeight() - dp(16);
    }

    private void scrollToBottom() {
        // Not fullScroll(): it would move the focus (and the keyboard) off the prompt
        scroll.post(() -> scroll.scrollTo(0, Math.max(0, screen.getHeight() - scroll.getHeight())));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Android 11+: draw edge to edge, keeping the content clear of system bars and keyboard. */
    @SuppressWarnings("deprecation") // only called where edge to edge is not yet the default
    private void fitInsets(View root) {
        if (Build.VERSION.SDK_INT < 30) return; // older: adjustResize between the bars
        if (Build.VERSION.SDK_INT < 35) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
    }
}
