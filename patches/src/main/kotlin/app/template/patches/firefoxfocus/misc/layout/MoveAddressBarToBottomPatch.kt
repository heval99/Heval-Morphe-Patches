package app.template.patches.firefoxfocus.misc.layout

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_FIREFOX_FOCUS
import org.w3c.dom.Element

private const val BROWSER_LAYOUT = "res/layout/fragment_browser.xml"
private const val TOOLBAR = "mozilla.components.browser.toolbar.BrowserToolbar"

/**
 * Firefox Focus pins its address bar to the top of `fragment_browser.xml` (the toolbar's
 * android:layout_gravity is 0x30 = top). The layout is a CoordinatorLayout that fills the screen,
 * so changing the toolbar gravity to bottom moves the whole bar without touching the engine view;
 * the toolbar keeps its id, height, elevation and behaviour.
 */
@Suppress("unused")
val moveAddressBarToBottomPatch = resourcePatch(
    name = "Move address bar to the bottom",
    description = "Moves the address bar (browser toolbar) from the top to the bottom of the screen.",
) {
    compatibleWith(COMPATIBILITY_FIREFOX_FOCUS)

    execute {
        document(BROWSER_LAYOUT).use { doc ->
            val toolbars = doc.getElementsByTagName(TOOLBAR)
                .let { list -> (0 until list.length).map { list.item(it) as Element } }
            val toolbar = toolbars.singleOrNull()
                ?: throw PatchException("$BROWSER_LAYOUT: expected one $TOOLBAR, found ${toolbars.size}")
            if (toolbar.getAttribute("android:id") != "@id/browserToolbar") {
                throw PatchException("$BROWSER_LAYOUT: the toolbar id is ${toolbar.getAttribute("android:id")}")
            }
            toolbar.setAttribute("android:layout_gravity", "bottom")
        }
    }
}
