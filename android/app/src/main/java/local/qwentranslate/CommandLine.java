package local.qwentranslate;

import java.util.ArrayList;
import java.util.List;

/** Splits a typed command into words the way a POSIX shell would (without expansions). */
final class CommandLine {
    private CommandLine() {}

    /** The first whitespace-separated word, as typed (empty for a blank line). */
    static String firstWord(String line) {
        String s = line.trim();
        int end = 0;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        return s.substring(0, end);
    }

    /**
     * Words of {@code line}: whitespace separates them, '…' quotes literally, "…"
     * quotes with \" \\ \$ \` escapes, a backslash escapes the next character, and a
     * leading ~ (alone or before /) stands for {@code home}.
     *
     * @throws IllegalArgumentException on an unterminated quote
     */
    static List<String> split(String line, String home) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        int n = line.length();
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
                continue;
            }
            if (!inWord && c == '~' && (i + 1 == n || line.charAt(i + 1) == '/'
                    || Character.isWhitespace(line.charAt(i + 1)))) {
                word.append(home);
                inWord = true;
                continue;
            }
            inWord = true;
            switch (c) {
                case '\'': {
                    int close = line.indexOf('\'', i + 1);
                    if (close < 0) throw new IllegalArgumentException("unterminated quote: '");
                    word.append(line, i + 1, close);
                    i = close;
                    break;
                }
                case '"': {
                    int j = i + 1;
                    for (; j < n && line.charAt(j) != '"'; j++) {
                        char d = line.charAt(j);
                        if (d == '\\' && j + 1 < n && "\"\\$`".indexOf(line.charAt(j + 1)) >= 0) {
                            d = line.charAt(++j);
                        }
                        word.append(d);
                    }
                    if (j >= n) throw new IllegalArgumentException("unterminated quote: \"");
                    i = j;
                    break;
                }
                case '\\':
                    // A trailing backslash stays as it is
                    word.append(i + 1 < n ? line.charAt(++i) : c);
                    break;
                default:
                    word.append(c);
            }
        }
        if (inWord) words.add(word.toString());
        return words;
    }

    /** Quotes {@code word} so that {@link #split} gives it back unchanged. */
    static String quote(String word) {
        if (!word.isEmpty() && word.chars().noneMatch(c -> Character.isWhitespace(c)
                || "'\"\\$`~#&|;<>()*?[]{}!".indexOf(c) >= 0)) {
            return word;
        }
        return "'" + word.replace("'", "'\\''") + "'";
    }
}
