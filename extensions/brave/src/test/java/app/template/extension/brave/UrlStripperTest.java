package app.template.extension.brave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class UrlStripperTest {
    @Test
    public void stripsMarketingAndClickIds() {
        assertEquals("https://example.com/a?id=7",
                UrlStripper.sanitize("https://example.com/a?utm_source=x&id=7&gclid=abc&fbclid=1&utm_medium=m"));
        assertEquals("https://example.com/a", UrlStripper.sanitize("https://example.com/a?utm_source=x&msclkid=1"));
        assertEquals("https://example.com/a#frag", UrlStripper.sanitize("https://example.com/a?igshid=1#frag"));
    }

    @Test
    public void keepsFunctionalParameters() {
        String url = "https://www.youtube.com/watch?v=abc&t=42s&list=PL1";
        assertEquals(url, UrlStripper.sanitize(url));
        String search = "https://example.com/search?q=utm_source&page=2&ref=home&token=zz";
        assertEquals(search, UrlStripper.sanitize(search));
        assertEquals("https://example.com/p", UrlStripper.sanitize("https://example.com/p"));
        assertEquals("https://example.com/p?", UrlStripper.sanitize("https://example.com/p?"));
    }

    @Test
    public void siIsOnlyStrippedOnYouTubeAndSpotify() {
        assertEquals("https://youtu.be/abc", UrlStripper.sanitize("https://youtu.be/abc?si=XYZ"));
        assertEquals("https://open.spotify.com/track/1", UrlStripper.sanitize("https://open.spotify.com/track/1?si=q"));
        assertEquals("https://shop.example.com/x?si=42", UrlStripper.sanitize("https://shop.example.com/x?si=42"));
        assertEquals("https://notyoutube.com/x?si=42", UrlStripper.sanitize("https://notyoutube.com/x?si=42"));
    }

    @Test
    public void decodesEscapedKeysAndKeepsRawPairs() {
        assertEquals("https://e.com/?a=%41", UrlStripper.sanitize("https://e.com/?%75tm_source=1&a=%41"));
        assertEquals("https://e.com/?keep=1", UrlStripper.sanitize("https://e.com/?UTM_Campaign=1&keep=1"));
    }

    @Test
    public void cleansUrlsInsideText() {
        assertEquals("Look: https://e.com/a?x=1 now https://f.com/b",
                UrlStripper.sanitize("Look: https://e.com/a?utm_source=t&x=1 now https://f.com/b?fbclid=9"));
        assertEquals("no link here", UrlStripper.sanitize("no link here"));
        assertNull(UrlStripper.sanitize(null));
        assertEquals("", UrlStripper.sanitize(""));
    }

    @Test
    public void trailingPunctuationIsNotPartOfTheUrl() {
        assertEquals("(see https://x.com/p).", UrlStripper.sanitize("(see https://x.com/p?utm_source=tw)."));
        assertEquals("Visit https://x.com/p, then https://y.com/q!",
                UrlStripper.sanitize("Visit https://x.com/p?fbclid=1, then https://y.com/q?gclid=2!"));
        assertEquals("\"https://x.com/p\"", UrlStripper.sanitize("\"https://x.com/p?utm_medium=a\""));
        // a balanced ")" belongs to the URL
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", UrlStripper.sanitize("https://en.wikipedia.org/wiki/Foo_(bar)?utm_source=x"));
    }

    @Test
    public void unchangedInputIsTheSameInstance() {
        String plain = "  https://e.com/a?q=1  ";
        assertSame(plain, UrlStripper.sanitize(plain));
        String text = "no tracking in https://e.com/a?id=2. ok";
        assertSame(text, UrlStripper.sanitize(text));
        String html = "<a href=\"https://e.com/a?id=2\">x</a>";
        assertSame(html, UrlStripper.sanitizeHtml(html));
        assertEquals(" https://e.com/a ", UrlStripper.sanitize(" https://e.com/a?utm_source=x "));
    }

    @Test
    public void htmlLinksAreCleanedAndEscapedAgain() {
        assertEquals("<a href=\"https://e.com/a?id=1&amp;x=2\">https://e.com/a?utm_source=t</a>",
                UrlStripper.sanitizeHtml("<a href=\"https://e.com/a?utm_source=t&amp;id=1&amp;x=2\">https://e.com/a?utm_source=t</a>"));
        assertEquals("<img src='https://e.com/i.png'>", UrlStripper.sanitizeHtml("<img src='https://e.com/i.png?fbclid=9'>"));
        assertNull(UrlStripper.sanitizeHtml(null));
    }

    @Test
    public void malformedInputIsReturnedUnchanged() {
        assertEquals("https://e.com/?a=%zz&b", UrlStripper.sanitize("https://e.com/?a=%zz&b"));
        assertTrue(UrlStripper.isTrackingParam("utm_x", false));
    }
}
