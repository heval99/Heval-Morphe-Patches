package app.template.patches.tipsbladet.misc.telemetry

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_TIPSLABET
import app.template.patches.shared.addInstructionsChecked
import org.w3c.dom.Element

/**
 * Turns off Firebase Analytics, Crashlytics, Sessions, Performance and advertising-ID collection
 * through the manifest switches. OneSignal (push) is not touched.
 */
private val disableTelemetryManifestPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_TIPSLABET)

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
                // Overwrite a stock entry in place (the app declares analytics and Crashlytics),
                // otherwise add one under <application>.
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
 * The app logs every event through @react-native-firebase/analytics, and its consent flow can call
 * setAnalyticsCollectionEnabled(true) at runtime, which overrides the manifest switch. Overwriting
 * the boolean argument with Boolean.FALSE keeps the promise chain intact while every call ends in
 * "collection disabled"; the manifest switches stop the automatic first_open/session/screen
 * collection before the first event.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables analytics, crash reporting and session collection.",
) {
    compatibleWith(COMPATIBILITY_TIPSLABET)

    dependsOn(disableTelemetryManifestPatch)

    execute {
        val method = AnalyticsCollectionSwitchFingerprint.method
        val register = method.implementation!!.registerCount - method.parameterTypes.size
        method.addInstructionsChecked(
            0,
            "sget-object v$register, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;",
        )
    }
}
