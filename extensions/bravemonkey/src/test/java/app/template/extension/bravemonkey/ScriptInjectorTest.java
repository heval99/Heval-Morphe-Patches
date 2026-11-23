package app.template.extension.bravemonkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Payload shape, escaping and the install validator. */
public class ScriptInjectorTest {
    @Test
    public void escapesEveryBreakoutCharacter() {
        assertEquals("\"a\\\"b\\'c\\\\d\\ne\\x3c/script>\\u2028\\x00\"",
                ScriptInjector.q("a\"b'c\\d\ne</script> \u0000"));
    }

    @Test
    public void hostileNameStaysInsideItsLiteral() {
        Userscript s = UserscriptMetadataParser.parse(
                "// ==UserScript==\n// @name x']);alert(1);//\"\n// @match https://a.com/*\n// ==/UserScript==\n",
                null, "");
        s.source = ""; // the script's own text is code by design; check only interpolated values
        String payload = ScriptInjector.buildPayload(s);
        assertFalse(payload.contains("x']);alert(1)"));
        assertTrue(payload.contains("name:\"x\\']);alert(1);//\\\"\""));
        assertTrue(payload.contains("\"[Brave Userscripts x\\']);alert(1);//\\\"]\""));
    }

    @Test
    public void payloadUsesTheBraveMarkerAndHandler() {
        Userscript s = UserscriptMetadataParser.parse(
                "// ==UserScript==\n// @name a\n// @match https://a.com/*\n// @run-at document-start\n"
                        + "// ==/UserScript==\nfoo() // no newline at the end", null, "");
        String payload = ScriptInjector.buildPayload(s);
        assertTrue(payload.contains("window.__BraveMonkeyLoaded"));
        assertTrue(payload.contains("scriptHandler:'Brave Userscripts'"));
        assertTrue(payload.contains("unsafeWindow=window"));
        assertTrue(payload.contains("foo() // no newline at the end\n}catch"));
        assertTrue(payload.endsWith("run();})();"));
    }

    @Test
    public void clickBridgeEscapesThePackage() {
        String js = ScriptInjector.installClickBridge("com.brave.browser'\"");
        assertTrue(js.contains("var pkg=\"com.brave.browser\\'\\\"\";"));
        assertTrue(js.contains("scheme=bravemonkey-install;"));
        // No dead click: if the intent does not open the review, the file URL itself is opened.
        assertTrue(js.contains("setTimeout(function(){if(document.visibilityState==='visible')location.href=u},1500);"));
    }

    @Test
    public void installRequestValidator() {
        assertTrue(ViolentmonkeyCompat.isAllowedInstallRequest("https://update.greasyfork.org/scripts/1/a.user.js"));
        assertTrue(ViolentmonkeyCompat.isAllowedInstallRequest("https://raw.githubusercontent.com/u/r/main/x.user.css"));
        assertTrue(ViolentmonkeyCompat.isAllowedInstallRequest("https://greasyfork.org/en/scripts/12345-name"));
        assertFalse(ViolentmonkeyCompat.isAllowedInstallRequest("http://update.greasyfork.org/scripts/1/a.user.js"));
        assertFalse(ViolentmonkeyCompat.isAllowedInstallRequest("https://example.com/page.html"));
        assertFalse(ViolentmonkeyCompat.isAllowedInstallRequest("javascript:alert(1)//.user.js"));
        assertFalse(ViolentmonkeyCompat.isAllowedInstallRequest("file:///sdcard/a.user.js"));
        assertFalse(ViolentmonkeyCompat.isAllowedInstallRequest(null));
    }

    @Test
    public void redactDropsQueryFragmentCredentialsAndPort() {
        assertEquals("https://example.com/a/b", Logger.redact("https://user:pw@example.com:8443/a/b?token=1#x"));
        assertEquals("https://example.com/", Logger.redact("https://example.com?q=1"));
    }
}
