package app.template.extension.bravemonkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** @match / @include / @exclude / @exclude-match rules. */
public class UrlPatternMatcherTest {
    private static Userscript script(String... matches) {
        Userscript s = new Userscript();
        s.matches.addAll(Arrays.asList(matches));
        return s;
    }

    @Test
    public void matchGlobOnHostAndPath() {
        Userscript s = script("https://example.com/foo/*");
        assertTrue(UrlPatternMatcher.matches(s, "https://example.com/foo/bar"));
        assertTrue(UrlPatternMatcher.matches(s, "https://example.com/foo/bar?x=1#frag"));
        assertFalse(UrlPatternMatcher.matches(s, "https://example.com/other"));
        assertFalse(UrlPatternMatcher.matches(s, "https://www.example.com/foo/bar"));
    }

    @Test
    public void subdomainWildcardCoversBareDomain() {
        Userscript s = script("*://*.example.com/*");
        assertTrue(UrlPatternMatcher.matches(s, "https://example.com/"));
        assertTrue(UrlPatternMatcher.matches(s, "http://a.b.example.com/x"));
        assertFalse(UrlPatternMatcher.matches(s, "https://badexample.com/"));
        assertFalse(UrlPatternMatcher.matches(s, "https://example.com.evil.net/"));
    }

    @Test
    public void schemeRestrictions() {
        assertFalse(UrlPatternMatcher.matches(script("https://example.com/*"), "http://example.com/"));
        assertTrue(UrlPatternMatcher.matches(script("http://example.com/*"), "http://example.com/"));
        // "*" scheme means http or https only, and nothing outside http(s) is injectable.
        assertFalse(UrlPatternMatcher.matchesChromePattern("*://*/*", "ftp://example.com/"));
        assertFalse(UrlPatternMatcher.matches(script("*://*/*"), "file:///sdcard/a.html"));
        assertFalse(UrlPatternMatcher.matches(script("<all_urls>"), "chrome://settings/"));
        assertFalse(UrlPatternMatcher.matches(script("<all_urls>"), "javascript:alert(1)"));
    }

    @Test
    public void allUrls() {
        Userscript s = script("<all_urls>");
        assertTrue(UrlPatternMatcher.matches(s, "https://anything.example/"));
        assertTrue(UrlPatternMatcher.matches(s, "http://10.0.2.2:8766/a.html"));
    }

    @Test
    public void portInPattern() {
        assertTrue(UrlPatternMatcher.matches(script("http://10.0.2.2:8766/*"), "http://10.0.2.2:8766/a.html"));
        assertFalse(UrlPatternMatcher.matches(script("http://10.0.2.2:8766/*"), "http://10.0.2.2:9000/a.html"));
        assertTrue(UrlPatternMatcher.matches(script("http://10.0.2.2/*"), "http://10.0.2.2:8766/a.html"));
    }

    @Test
    public void includeGlobAndRegex() {
        Userscript s = new Userscript();
        s.includes.add("https://*.wikipedia.org/wiki/*");
        assertTrue(UrlPatternMatcher.matches(s, "https://en.wikipedia.org/wiki/Brave"));
        assertFalse(UrlPatternMatcher.matches(s, "https://en.wikipedia.org/w/index.php"));

        Userscript r = new Userscript();
        r.includes.add("/^https:\\/\\/news\\.example\\.(com|org)\\/\\d+$/");
        assertTrue(UrlPatternMatcher.matches(r, "https://news.example.org/123"));
        assertFalse(UrlPatternMatcher.matches(r, "https://news.example.net/123"));

        Userscript broken = new Userscript();
        broken.includes.add("/([unclosed/");
        assertFalse(UrlPatternMatcher.matches(broken, "https://example.com/"));
    }

    @Test
    public void questionMarkIsSingleCharGlob() {
        assertTrue(UrlPatternMatcher.matchesInclude("https://example.com/a?c", "https://example.com/abc"));
        assertFalse(UrlPatternMatcher.matchesInclude("https://example.com/a?c", "https://example.com/abbc"));
    }

    @Test
    public void excludeAndExcludeMatchWin() {
        Userscript s = script("https://example.com/*");
        s.excludes.add("*/private/*");
        s.excludeMatches.add("https://example.com/admin*");
        assertTrue(UrlPatternMatcher.matches(s, "https://example.com/public/page"));
        assertFalse(UrlPatternMatcher.matches(s, "https://example.com/private/page"));
        assertFalse(UrlPatternMatcher.matches(s, "https://example.com/admin/settings"));
    }

    @Test
    public void disabledOrEmptyNeverMatches() {
        Userscript s = script("https://example.com/*");
        s.enabled = false;
        assertFalse(UrlPatternMatcher.matches(s, "https://example.com/"));
        assertFalse(UrlPatternMatcher.matches(new Userscript(), "https://example.com/"));
        assertFalse(UrlPatternMatcher.matches(script("https://example.com/*"), null));
        assertFalse(UrlPatternMatcher.matches(script("https://example.com/*"), "  "));
    }

    @Test
    public void countMatches() {
        assertEquals(2, UrlPatternMatcher.countMatches(Arrays.asList(
                script("https://example.com/*"), script("<all_urls>"), script("https://other.com/*")),
                "https://example.com/x"));
    }
}
