package app.template.patches.herning.misc.telemetry

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_HERNING
import app.template.patches.shared.misc.telemetry.forceFirebaseManifestSwitches

/**
 * Textalk's reader funnels every analytics event - the app's log/send helpers, the consent-replay
 * queue and the queued-before-ready path - through one dispatcher that fans out to Firebase
 * Analytics, Prenlytics/Pulse and any configured Kantar Sifo reporter. Making that dispatcher
 * return at once drops all of it; the reader, purchases and push flows are untouched.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables analytics, crash reporting and session collection.",
) {
    compatibleWith(COMPATIBILITY_HERNING)

    execute {
        // Firebase Analytics, Crashlytics, Sessions and ad-ID collection off, as the manifest
        // switches would set them but in the bytecode (no resource re-encode, rule 12). The app
        // bundles no Firebase Performance. Firebase Messaging (push) is not touched; the app's own
        // consent management keeps working.
        forceFirebaseManifestSwitches(
            app = "Herning Folkeblad",
            analytics = true,
            crashlytics = true,
            sessions = true,
            performance = false,
        )

        TextalkAnalyticsDispatchFingerprint.method.returnEarly()
    }
}
