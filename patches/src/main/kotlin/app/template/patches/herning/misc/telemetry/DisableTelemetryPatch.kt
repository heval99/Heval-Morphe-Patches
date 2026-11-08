package app.template.patches.herning.misc.telemetry

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_HERNING
import org.w3c.dom.Element

/**
 * Turns off Firebase Analytics, Crashlytics, Sessions, Performance and advertising-ID collection
 * through the manifest switches. Firebase Messaging (push) is not touched; the app's own consent
 * management keeps working.
 */
private val disableTelemetryManifestPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_HERNING)

    execute {
        document("AndroidManifest.xml").use { doc ->
            val applications = doc.getElementsByTagName("application")
            if (applications.length == 0) throw PatchException("AndroidManifest.xml has no <application>")
            val application = applications.item(0) as Element

            val existing = buildMap {
                val metas = application.getElementsByTagName("meta-data")
                for (i in 0 until metas.length) {
                    val meta = metas.item(i) as? Element ?: continue
                    if (meta.parentNode != application) continue
                    put(meta.getAttribute("android:name"), meta)
                }
            }

            for ((name, value) in mapOf(
                "firebase_analytics_collection_enabled" to "false",
                "firebase_analytics_collection_deactivated" to "true",
                "firebase_crashlytics_collection_enabled" to "false",
                "firebase_performance_collection_deactivated" to "true",
                "firebase_sessions_enabled" to "false",
                "google_analytics_adid_collection_enabled" to "false",
            )) {
                // Overwrite a stock entry in place (the app declares the analytics and Crashlytics
                // switches), otherwise add one under <application>.
                val meta = existing[name] ?: doc.createElement("meta-data").also {
                    it.setAttribute("android:name", name)
                    application.appendChild(it)
                }
                meta.setAttribute("android:value", value)
            }
        }
    }
}

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

    dependsOn(disableTelemetryManifestPatch)

    execute {
        TextalkAnalyticsDispatchFingerprint.method.returnEarly()
    }
}
