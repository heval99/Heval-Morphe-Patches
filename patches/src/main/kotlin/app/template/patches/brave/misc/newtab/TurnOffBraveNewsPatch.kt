package app.template.patches.brave.misc.newtab

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.template.patches.brave.BraveNewsPolicyFingerprint
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL

@Suppress("unused")
val turnOffBraveNewsPatch = bytecodePatch(
    name = "Turn off Brave News",
    description = "Removes the Brave News feed from the new tab page.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    // Brave's own "News is turned off by policy" check (also what Brave Origin sets). The
    // new tab page, its Settings row and the feed start-up all ask it, so reporting true
    // switches the feature off everywhere. Sponsored new tab images are handled by
    // "Disable ads"; the tile grid already uses four columns in this build.
    // Brave Origin rewrites the same method (and removes the string), so the match is resolved
    // in execute through the shared fingerprint and the forced true is added in finalize, after
    // every execute: News wins whichever patch ran first.
    execute {
        BraveNewsPolicyFingerprint.method
    }

    finalize {
        BraveNewsPolicyFingerprint.method.returnEarly(true)
    }
}
