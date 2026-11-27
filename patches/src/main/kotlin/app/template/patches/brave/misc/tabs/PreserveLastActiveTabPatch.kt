package app.template.patches.brave.misc.tabs

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL

@Suppress("unused")
val preserveLastActiveTabPatch = bytecodePatch(
    name = "Preserve last active tab",
    description = "Keeps your last open tab on start instead of opening a new tab page after a break.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    execute {
        // ChromeTabbedActivity's "open a new tab page on start" check, driven by Brave's
        // new-tab-opening-screen setting and a 12 hour idle timer. Reporting false keeps the tab.
        Fingerprint(
            definingClass = "Lorg/chromium/chrome/browser/ChromeTabbedActivity;",
            returnType = "Z",
            parameters = emptyList(),
            strings = listOf(string("brave_new_tab_page_opening_screen")),
        ).method.returnEarly(false)
    }
}
