package app.template.extension.bravemonkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;

/** The pure parts of BraveBridge: GURL spec pick and the javascript: URL builder. */
public class BraveBridgeTest {
    /** The GURL getter values measured on Brave 1.100.58 (e .. n). */
    private static List<String> measured() {
        StringBuilder nul = new StringBuilder();
        for (int i = 0; i < 84; i++) {
            if (i > 0) nul.append('\u0000');
            nul.append("http://10.0.2.2:8766/a.html");
        }
        return Arrays.asList(
                "10.0.2.2",                    // e
                "/a.html",                     // g
                "8766",                        // h
                "",                            // i
                "http",                        // j
                "http://10.0.2.2:8766/a.html", // k
                "http://10.0.2.2:8766/a.html", // l
                nul.toString());               // n
    }

    @Test
    public void picksTheSpecFromTheMeasuredCandidates() {
        assertEquals("http://10.0.2.2:8766/a.html", BraveBridge.pickUrl(measured()));
    }

    @Test
    public void rejectsBareSchemeAndNulSerialisation() {
        assertNull(BraveBridge.pickUrl(Arrays.asList("http", "10.0.2.2", null, "")));
        assertNull(BraveBridge.pickUrl(Arrays.asList("https://a.com/\u0000x")));
        assertNull(BraveBridge.pickUrl(null));
        assertEquals("https://a.com/", BraveBridge.pickUrl(
                Arrays.asList("https://a.com/\u0000https://a.com/", "https://a.com/", "https")));
    }

    @Test
    public void percentEncodeMatchesUriEncodeRules() {
        assertEquals("abcXYZ019_-!.~'()*", BraveBridge.percentEncode("abcXYZ019_-!.~'()*"));
        assertEquals("%20%2B%25%26%3D%3F%23%2F%3A%3B%22%3C%3E%7B%7D%0A",
                BraveBridge.percentEncode(" +%&=?#/:;\"<>{}\n"));
        // é is two UTF-8 bytes; the emoji (a surrogate pair) is one code point, four bytes.
        assertEquals("%C3%A9", BraveBridge.percentEncode("é"));
        assertEquals("%F0%9F%98%80", BraveBridge.percentEncode("😀"));
    }

    @Test
    public void javascriptUrlRoundTrips() throws Exception {
        String body = "(function(){var s='a b+c%d&e#f?g\\n';console.log(s, \"é😀\");})();";
        String url = BraveBridge.javascriptUrl(body);
        assertTrue(url.startsWith("javascript:"));
        String encoded = url.substring("javascript:".length());
        assertFalse(encoded.contains(" "));
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("#"));
        assertEquals(BraveBridge.wrap(body), URLDecoder.decode(encoded, "UTF-8"));
    }

    @Test
    public void wrapEvaluatesToUndefinedAndSurvivesTrailingComment() {
        String wrapped = BraveBridge.wrap("foo(); // trailing comment");
        assertTrue(wrapped.startsWith("void(function(){"));
        assertTrue(wrapped.endsWith("\n}())"));
    }
}
