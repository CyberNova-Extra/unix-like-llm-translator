package local.qwentranslate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalBufferTest {
    /** Plain-text screen that records the style of every character. */
    private static final class TextScreen implements TerminalBuffer.Screen {
        final StringBuilder text = new StringBuilder();
        final StringBuilder styles = new StringBuilder();

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
            text.replace(start, end, s.toString());
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < s.length(); i++) marks.append((char) ('0' + style));
            styles.replace(start, end, marks.toString());
        }
    }

    private final TextScreen screen = new TextScreen();
    private final TerminalBuffer term = new TerminalBuffer(screen);

    @Test
    public void appendsText() {
        term.write("hello ", TerminalBuffer.NORMAL);
        term.write("world\n", TerminalBuffer.NORMAL);
        assertEquals("hello world\n", screen.text.toString());
    }

    @Test
    public void carriageReturnRedrawsTheLine() {
        term.write("Downloading\n", TerminalBuffer.STDERR);
        term.write("  5.0%  1.0 / 20.0 MiB\r", TerminalBuffer.STDERR);
        term.write(" 55.0% 11.0 / 20.0 MiB", TerminalBuffer.STDERR);
        term.write("\r100.0% 20.0 / 20.0 MiB\n", TerminalBuffer.STDERR);
        assertEquals("Downloading\n100.0% 20.0 / 20.0 MiB\n", screen.text.toString());
    }

    @Test
    public void shorterRedrawKeepsTheRestOfTheLine() {
        term.write("abcdef\rXY", TerminalBuffer.NORMAL);
        assertEquals("XYcdef", screen.text.toString());
        term.write("\n", TerminalBuffer.NORMAL);
        assertEquals("XYcdef\n", screen.text.toString());
    }

    @Test
    public void eraseInLineAndBackspace() {
        term.write("abcdef\r\u001b[Kxy", TerminalBuffer.NORMAL);
        assertEquals("xy", screen.text.toString());
        term.write("z\b\bQ", TerminalBuffer.NORMAL);
        assertEquals("xQz", screen.text.toString());
    }

    @Test
    public void escapeSequencesAreDroppedEvenWhenSplit() {
        term.write("\u001b[1;3", TerminalBuffer.NORMAL);
        term.write("1mred\u001b[0m \u001b]0;title\u0007ok\u0007", TerminalBuffer.NORMAL);
        assertEquals("red ok", screen.text.toString());
    }

    @Test
    public void tabsPadToTheNextStop() {
        term.write("ab\tc", TerminalBuffer.NORMAL);
        assertEquals("ab      c", screen.text.toString());
    }

    @Test
    public void stylesFollowTheText() {
        term.write("$ ", TerminalBuffer.PROMPT);
        term.write("hi\n", TerminalBuffer.NORMAL);
        term.write("warn\n", TerminalBuffer.STDERR);
        assertEquals("$ hi\nwarn\n", screen.text.toString());
        assertEquals("1100022222", screen.styles.toString());
    }

    @Test
    public void ensureNewlineOnlyEndsUnfinishedLines() {
        term.ensureNewline();
        assertEquals("", screen.text.toString());
        term.write("partial", TerminalBuffer.NORMAL);
        term.ensureNewline();
        term.ensureNewline();
        assertEquals("partial\n", screen.text.toString());
        term.write("x\r", TerminalBuffer.NORMAL);
        term.ensureNewline();
        assertEquals("partial\nx\n", screen.text.toString());
    }

    @Test
    public void trimsWholeLinesFromTheTop() {
        String line = "0123456789012345678901234567890123456789012345678901234567890123\n";
        int lines = TerminalBuffer.MAX_CHARS / line.length() + 10;
        for (int i = 0; i < lines; i++) term.write(line, TerminalBuffer.NORMAL);
        term.write("last", TerminalBuffer.NORMAL);
        String text = screen.text.toString();
        assertEquals(true, text.length() <= TerminalBuffer.MAX_CHARS);
        assertEquals(true, text.startsWith(line));
        assertEquals(true, text.endsWith(line + "last"));
        // The cursor still points at the last line
        term.write("\rLAST", TerminalBuffer.NORMAL);
        assertEquals(true, screen.text.toString().endsWith(line + "LAST"));
    }
}
