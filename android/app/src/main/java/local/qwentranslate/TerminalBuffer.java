package local.qwentranslate;

/**
 * Terminal-style text model. Output is written at a cursor on the last line:
 * {@code \r} returns to the start of that line (so progress bars redraw in place),
 * {@code \b} steps back, {@code \t} pads to the next tab stop, {@code ESC[K}
 * erases the line and other escape sequences are dropped. Once the text exceeds
 * {@link #MAX_CHARS}, whole lines are trimmed from the top.
 *
 * <p>Plain Java over a {@link Screen}, so it is unit-tested off-device.
 */
final class TerminalBuffer {
    /** Text storage; on Android a SpannableStringBuilder shown by a TextView. */
    interface Screen {
        int length();

        char charAt(int index);

        /** Replaces [start, end) with {@code text}, drawn in {@code style}. */
        void replace(int start, int end, CharSequence text, int style);
    }

    static final int NORMAL = 0;
    static final int PROMPT = 1;
    static final int STDERR = 2;
    static final int INFO = 3;
    static final int ERROR = 4;

    static final int MAX_CHARS = 256 * 1024;

    private static final int TAB = 8;
    // Escape sequence parser states
    private static final int GROUND = 0;
    private static final int ESC = 1;
    private static final int CSI = 2;
    private static final int OSC = 3;
    private static final int OSC_ESC = 4;

    private final Screen screen;
    /** Start of the last line, where the cursor lives. */
    private int lineStart;
    /** Absolute write position, between lineStart and the end of the text. */
    private int cursor;
    private int state = GROUND;
    private final StringBuilder params = new StringBuilder();
    /** Printable characters waiting to be written at the cursor. */
    private final StringBuilder run = new StringBuilder();

    TerminalBuffer(Screen screen) {
        this.screen = screen;
        lineStart = cursor = screen.length();
    }

    void write(CharSequence text, int style) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (state != GROUND) {
                escape(c);
                continue;
            }
            switch (c) {
                case '\r':
                    flush(style);
                    cursor = lineStart;
                    break;
                case '\n':
                    flush(style);
                    newline(style);
                    break;
                case '\b':
                    flush(style);
                    if (cursor > lineStart) cursor--;
                    break;
                case '\t': {
                    int column = cursor - lineStart + run.length();
                    for (int n = TAB - column % TAB; n > 0; n--) run.append(' ');
                    break;
                }
                case 0x1b:
                    flush(style);
                    state = ESC;
                    break;
                default:
                    // Other control characters (BEL, …) have nothing to show
                    if (c >= 0x20 && c != 0x7f) run.append(c);
            }
        }
        flush(style);
        trim();
    }

    /** Ends a partly written line, so what follows starts on a line of its own. */
    void ensureNewline() {
        if (screen.length() > lineStart) newline(NORMAL);
    }

    /** Forgets the text, after the screen was emptied by its owner. */
    void reset() {
        lineStart = cursor = screen.length();
        state = GROUND;
        run.setLength(0);
    }

    private void flush(int style) {
        if (run.length() == 0) return;
        // Overwrite what the line holds after the cursor (after a \r), then extend it
        int end = Math.min(cursor + run.length(), screen.length());
        screen.replace(cursor, end, run, style);
        cursor += run.length();
        run.setLength(0);
    }

    private void newline(int style) {
        // A line feed leaves the line as it is, even when the cursor was moved back
        int end = screen.length();
        screen.replace(end, end, "\n", style);
        lineStart = cursor = screen.length();
    }

    private void escape(char c) {
        switch (state) {
            case ESC:
                state = c == '[' ? CSI : c == ']' ? OSC : GROUND;
                params.setLength(0);
                break;
            case CSI:
                if (c >= 0x40 && c <= 0x7e) {
                    if (c == 'K') eraseInLine(params.toString());
                    state = GROUND;
                } else {
                    params.append(c);
                }
                break;
            case OSC:
                // Terminated by BEL or ESC \
                if (c == 0x07) state = GROUND;
                else if (c == 0x1b) state = OSC_ESC;
                break;
            default:
                state = GROUND;
        }
    }

    /** ESC[K: 0 (default) erases to the end of the line, 1 to the cursor, 2 all of it. */
    private void eraseInLine(String mode) {
        int end = screen.length();
        switch (mode) {
            case "":
            case "0":
                screen.replace(cursor, end, "", NORMAL);
                break;
            case "1":
                screen.replace(lineStart, cursor, spaces(cursor - lineStart), NORMAL);
                break;
            case "2":
                screen.replace(lineStart, end, spaces(cursor - lineStart), NORMAL);
                break;
            default:
        }
    }

    private static String spaces(int n) {
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append(' ');
        return s.toString();
    }

    private void trim() {
        int length = screen.length();
        if (length <= MAX_CHARS) return;
        // Cut whole lines from the top, down to three quarters of the limit
        int cut = length - MAX_CHARS * 3 / 4;
        while (cut < lineStart && screen.charAt(cut - 1) != '\n') cut++;
        cut = Math.min(cut, lineStart);
        if (cut <= 0) return;
        screen.replace(0, cut, "", NORMAL);
        lineStart -= cut;
        cursor -= cut;
    }
}
