package app.template.extension.brave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class TabBridgeTest {
    @Test
    public void pickUrlTakesTheLongestConsistentSpec() {
        assertEquals("https://a.com/p?q=1#f",
                TabBridge.pickUrl(Arrays.asList("https://a.com/p?q=1#f", "https://a.com/", "q=1", "f")));
        assertNull(TabBridge.pickUrl(Arrays.asList("https://a.com/p", "https://b.com/")));
        assertNull(TabBridge.pickUrl(Arrays.asList("chrome://newtab", "x")));
    }

    @Test
    public void javascriptUrlIsPercentEncodedAndWrapped() {
        String url = TabBridge.javascriptUrl("a=1;");
        assertTrue(url.startsWith("javascript:void(function()"));
        assertEquals("a%20b%C3%A9", TabBridge.percentEncode("a bé"));
    }

    @Test
    public void bothScriptsAreEnabledSeparately() {
        PageScripts.enable(PageScripts.COPY_PASTE);
        String only = PageScripts.combined();
        assertTrue(only.contains("user-select"));
        assertTrue(!only.contains("disablepictureinpicture"));
        PageScripts.enable(PageScripts.PICTURE_IN_PICTURE);
        assertTrue(PageScripts.combined().contains("disablepictureinpicture"));
    }

    @Test
    public void copyPasteScriptIsNarrow() {
        String js = PageScripts.forceCopyPasteJs();
        assertTrue(!js.contains("stopImmediatePropagation"));
        assertTrue(js.contains("preventDefault"));
        assertTrue(js.contains(":not(button)"));
        assertTrue(js.contains(":not(input)"));
        assertTrue(js.contains("contenteditable"));
        assertTrue(js.contains("if(editable(t))return;"));
        assertTrue(js.contains("Math.random()"));
        assertTrue(!js.contains("window._"));
        assertTrue(!js.contains("_brave"));
        assertTrue(!js.contains("dragstart"));
    }

    @Test
    public void pictureInPictureScriptHasNoNamedGlobal() {
        String js = PageScripts.forcePictureInPictureJs();
        assertTrue(!js.contains("_brave"));
        assertTrue(js.contains("Symbol.for("));
    }
}