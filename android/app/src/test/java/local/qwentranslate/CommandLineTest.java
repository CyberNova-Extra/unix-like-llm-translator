package local.qwentranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class CommandLineTest {
    private static List<String> split(String line) {
        return CommandLine.split(line, "/home");
    }

    @Test
    public void splitsOnWhitespace() {
        assertEquals(Arrays.asList("translate", "--to", "ja", "hi"), split("  translate --to\tja  hi "));
        assertEquals(Arrays.asList(), split("   "));
    }

    @Test
    public void quotes() {
        assertEquals(Arrays.asList("a b", "it's", "x\"y", "$HOME"),
                split("'a b' \"it's\" \"x\\\"y\" '$HOME'"));
        assertEquals(Arrays.asList("ab", ""), split("a'b' \"\""));
        assertEquals(Arrays.asList("a b", "\\n"), split("a\\ b \"\\n\""));
    }

    @Test
    public void tildeIsHomeOnlyAtTheStartOfAWord() {
        assertEquals(Arrays.asList("/home/pic.png", "/home", "a~", "~x", "~/q"),
                split("~/pic.png ~ a~ ~x '~/q'"));
    }

    @Test
    public void unterminatedQuotesAreErrors() {
        assertThrows(IllegalArgumentException.class, () -> split("translate I'm"));
        assertThrows(IllegalArgumentException.class, () -> split("translate \"open"));
    }

    @Test
    public void quoteRoundTrips() {
        for (String w : new String[] {"plain", "two words", "it's", "", "$x", "~/a", "a\"b\\c"}) {
            assertEquals(Arrays.asList(w), split(CommandLine.quote(w)));
        }
        assertEquals("plain", CommandLine.quote("plain"));
        assertEquals("/data/user/0/x/cache/images/1.png",
                CommandLine.quote("/data/user/0/x/cache/images/1.png"));
    }

    @Test
    public void firstWord() {
        assertEquals("translate", CommandLine.firstWord("  translate --fast x"));
        assertEquals("你好", CommandLine.firstWord("你好 世界"));
        assertEquals("", CommandLine.firstWord("   "));
    }
}
