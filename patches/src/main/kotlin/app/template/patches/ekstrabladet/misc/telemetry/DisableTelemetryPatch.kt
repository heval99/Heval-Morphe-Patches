package app.template.patches.ekstrabladet.misc.telemetry

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_EKSTRABLADET
import app.template.patches.shared.addInstructionsChecked
import org.w3c.dom.Element

// Push notifications must keep working, so nothing here touches Firebase Messaging, Firebase
// Installations or the Pushwoosh services.

// Crashlytics is registered as a Firebase component; the app's own classes never call it, so the
// component can be removed from the component list entirely.
private const val CRASHLYTICS_REGISTRAR = "Lcom/google/firebase/crashlytics/CrashlyticsRegistrar;"

/**
 * Turns off Crashlytics, Firebase Sessions, Performance Monitoring and the advertising-ID
 * collection through their manifest switches. Ekstra Bladet has no analytics SDK (no
 * FirebaseAnalytics, AppsFlyer, Adjust, Branch, Amplitude or Mixpanel classes), so crash reporting
 * is the telemetry that exists.
 */
private val disableTelemetryManifestPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_EKSTRABLADET)

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
                "firebase_analytics_collection_deactivated" to "true",
                "firebase_crashlytics_collection_enabled" to "false",
                "firebase_performance_collection_deactivated" to "true",
                "firebase_sessions_enabled" to "false",
                "google_analytics_adid_collection_enabled" to "false",
            )) {
                // Overwrite a stock entry in place, otherwise add one under <application>.
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
 * Ekstra Bladet has no ad SDK at all: no ad-unit strings, no public ad API is referenced, and the
 * bundled Google Mobile Ads classes are only the manifest entry points. The only telemetry is
 * Firebase Crashlytics (plus the Sessions component that feeds it); Firebase Messaging is used by
 * Pushwoosh and stays untouched.
 *
 * The manifest switches turn collection off at startup. The bytecode half removes the Crashlytics
 * component from Firebase's component list, so crash reporting cannot initialise even if a later
 * version starts using it.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables Firebase crash reporting and session collection.",
) {
    compatibleWith(COMPATIBILITY_EKSTRABLADET)

    dependsOn(disableTelemetryManifestPatch)

    execute {
        val registrar = mutableClassDefBy(CRASHLYTICS_REGISTRAR)
        val getComponents = registrar.methods.singleOrNull {
            it.name == "getComponents" && it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/util/List;" && it.implementation != null
        } ?: throw PatchException("$CRASHLYTICS_REGISTRAR.getComponents() not found")

        getComponents.addInstructionsChecked(
            0,
            """
                sget-object v0, Ljava/util/Collections;->EMPTY_LIST:Ljava/util/List;
                return-object v0
            """,
        )
    }
}
