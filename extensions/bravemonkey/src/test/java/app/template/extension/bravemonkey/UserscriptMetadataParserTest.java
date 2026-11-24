package app.template.extension.bravemonkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Userscript header parsing. */
public class UserscriptMetadataParserTest {
    private static final String FULL = String.join("\n",
            "// ==UserScript==",
            "// @name         Example script",
            "// @name:de      Beispielskript",
            "// @namespace    https://example.com/ns",
            "// @version      1.2.3",
            "// @description  Does things",
            "// @match        https://example.com/*",
            "// @match        https://*.example.org/*",
            "// @include      /^https:\\/\\/foo\\.test\\//",
            "// @exclude      https://example.com/private/*",
            "// @exclude-match https://example.com/admin/*",
            "// @require      https://cdn.example.com/lib.js",
            "// @grant        none",
            "// @run-at       document-start",
            "// @noframes",
            "// ==/UserScript==",
            "",
            "console.log('hi');");

    @Test
    public void parsesEveryKey() {
        Userscript s = UserscriptMetadataParser.parse(FULL, "x.user.js", "https://example.com/x.user.js");
        assertEquals("Example script", s.name);
        assertEquals("https://example.com/ns", s.namespace);
        assertEquals("1.2.3", s.version);
        assertEquals("Does things", s.description);
        assertEquals(Arrays.asList("https://example.com/*", "https://*.example.org/*"), s.matches);
        assertEquals(Collections.singletonList("/^https:\\/\\/foo\\.test\\//"), s.includes);
        assertEquals(Collections.singletonList("https://example.com/private/*"), s.excludes);
        assertEquals(Collections.singletonList("https://example.com/admin/*"), s.excludeMatches);
        assertEquals(Collections.singletonList("https://cdn.example.com/lib.js"), s.requires);
        assertEquals(Collections.singletonList("none"), s.grants);
        assertEquals("document-start", s.runAt);
        assertTrue(s.noFrames);
        assertTrue(s.enabled);
        assertEquals(Userscript.KIND_JAVASCRIPT, s.kind);
        assertEquals("https://example.com/x.user.js", s.installUrl);
        assertTrue(s.id.startsWith("ms-"));
        assertEquals(UserscriptMetadataParser.stableId("https://example.com/ns", "Example script"), s.id);
    }

    @Test
    public void runAtValuesAndUnknownFallback() {
        assertEquals("document-idle", parseRunAt("document-idle"));
        assertEquals("document-body", parseRunAt("DOCUMENT-BODY"));
        assertEquals("document-end", parseRunAt("document-end"));
        assertEquals("document-end", parseRunAt("context-menu"));
        assertEquals("document-end", UserscriptMetadataParser.parse(
                "// ==UserScript==\n// @name a\n// @match https://a.com/*\n// ==/UserScript==", null, null).runAt);
    }

    private static String parseRunAt(String value) {
        return UserscriptMetadataParser.parse("// ==UserScript==\n// @name a\n// @match https://a.com/*\n"
                + "// @run-at " + value + "\n// ==/UserScript==", null, null).runAt;
    }

    @Test
    public void localizedNameOnlyWhenNoPlainName() {
        Userscript s = UserscriptMetadataParser.parse(
                "// ==UserScript==\n// @name:fr Mon script\n// @description:fr Bonjour\n"
                        + "// @match https://a.com/*\n// ==/UserScript==", "z.user.js", "");
        assertEquals("Mon script", s.name);
        assertEquals("Bonjour", s.description);
    }

    @Test
    public void noRulesOrNoHeaderInstallsDisabled() {
        Userscript noRules = UserscriptMetadataParser.parse(
                "// ==UserScript==\n// @name a\n// ==/UserScript==\n", null, "");
        assertFalse(noRules.enabled);
        Userscript noHeader = UserscriptMetadataParser.parse("alert(1)", "my-cool_script.user.js", "");
        assertFalse(noHeader.enabled);
        assertEquals("my cool script", noHeader.name);
    }

    @Test
    public void userStyleIsCss() {
        Userscript s = UserscriptMetadataParser.parse(
                "/* ==UserStyle==\n@name Dark\n@match https://a.com/*\n==/UserStyle== */\nbody{}", null, "");
        assertEquals(Userscript.KIND_CSS, s.kind);
        assertEquals("Dark", s.name);
        assertEquals(Collections.singletonList("https://a.com/*"), s.matches);
    }

    /** The owner's Sorare script header, lines 1-9 of the real file (header only, never the script body). */
    private static final String SORARE_HEADER = String.join("\n",
            "// ==UserScript==",
            "// @name         Sorare duplicate-version dropper",
            "// @namespace    sorare-set-companion",
            "// @version      2.3.0",
            "// @description  New Set (Divination), football and NBA (only the section you are in): keeps only the best version of each player and offers every other card of him for dropping after you review them. There is no collection bonus any more, so a second version is worth nothing. Pack check says KEEP or DELETE for every new card while you open a pack, as a badge right under the card on the page (2.3.0).",
            "// @match        https://sorare.com/*",
            "// @run-at       document-start",
            "// @grant        none",
            "// ==/UserScript==");

    @Test
    public void ownersSorareHeader() {
        Userscript s = UserscriptMetadataParser.parse(SORARE_HEADER + "\n",
                "Sorare duplicate-version dropper-2.3.0.user.js", "");
        assertEquals("Sorare duplicate-version dropper", s.name);
        assertEquals("2.3.0", s.version);
        assertEquals("document-start", s.runAt);
        assertEquals(Collections.singletonList("none"), s.grants);
        assertEquals(Collections.singletonList("https://sorare.com/*"), s.matches);
        assertTrue(s.enabled);
        assertTrue(ViolentmonkeyCompat.looksLikeUserscript(SORARE_HEADER));
        assertTrue(UrlPatternMatcher.matches(s, "https://sorare.com/football/my-cards"));
        assertFalse(UrlPatternMatcher.matches(s, "https://example.com/"));
        assertFalse(UrlPatternMatcher.matches(s, "http://sorare.com/football/my-cards"));
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        Userscript s = UserscriptMetadataParser.parse(FULL, "x.user.js", "https://example.com/x.user.js");
        s.requireSources.add("var lib=1;");
        Userscript back = Userscript.fromJson(s.toJson());
        assertEquals(s.id, back.id);
        assertEquals(s.matches, back.matches);
        assertEquals(s.excludeMatches, back.excludeMatches);
        assertEquals(s.requireSources, back.requireSources);
        assertEquals(s.source, back.source);
        assertEquals(s.runAt, back.runAt);
        assertEquals(s.noFrames, back.noFrames);
    }
}
