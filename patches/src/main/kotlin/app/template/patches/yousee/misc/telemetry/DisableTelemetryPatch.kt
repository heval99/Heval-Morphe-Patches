package app.template.patches.yousee.misc.telemetry

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_YOUSEE
import app.template.patches.shared.addInstructionsChecked
import org.w3c.dom.Element

// Push notifications must keep working, so nothing here touches Firebase Messaging, Firebase
// Installations or the app's notification services.

// The Firebase component that registers Google Analytics. The app's own classes never call an
// analytics API, but the component collects automatically once Firebase initialises.
private const val ANALYTICS_REGISTRAR =
    "Lcom/google/firebase/analytics/connector/internal/AnalyticsConnectorRegistrar;"

/**
 * Turns off Firebase Analytics, Crashlytics, Performance Monitoring, session collection and the
 * advertising-ID collection through their manifest switches.
 */
private val disableTelemetryManifestPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_YOUSEE)

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
 * YouSee Play has no ad SDK and its own classes never call an analytics API, but Firebase
 * Analytics, Crashlytics, Performance Monitoring and Sessions are registered as Firebase
 * components and collect automatically. Mixpanel is bundled but referenced only by its own
 * classes; AppsFlyer, Adjust, Branch and Amplitude exist only as strings.
 *
 * The manifest switches are what stop the SDKs at startup. The bytecode half removes the Google
 * Analytics component from Firebase's component list, so it cannot initialise even if the app
 * starts calling it in a later version. Firebase Messaging and Installations stay untouched.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables Firebase analytics, crash reporting, performance monitoring and session collection.",
) {
    compatibleWith(COMPATIBILITY_YOUSEE)

    dependsOn(disableTelemetryManifestPatch)

    execute {
        val registrar = mutableClassDefBy(ANALYTICS_REGISTRAR)
        val getComponents = registrar.methods.singleOrNull {
            it.name == "getComponents" && it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/util/List;" && it.implementation != null
        } ?: throw PatchException("$ANALYTICS_REGISTRAR.getComponents() not found")

        // Registering no components leaves Firebase Analytics uninitialised; everything else
        // (Messaging, Installations) is registered by its own components.
        getComponents.addInstructionsChecked(
            0,
            """
                sget-object v0, Ljava/util/Collections;->EMPTY_LIST:Ljava/util/List;
                return-object v0
            """,
        )
    }
}
