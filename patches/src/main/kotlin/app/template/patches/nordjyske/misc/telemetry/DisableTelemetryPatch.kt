package app.template.patches.nordjyske.misc.telemetry

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_NORDJYSKE
import org.w3c.dom.Element

// PugPig Bolt funnels every analytics event through AnalyticsDispatcher, which forwards to the
// configured providers (Firebase, Mixpanel, Facebook, and the publisher's own endpoint). The class
// and its public methods keep their names, so they are the stable anchors.
private const val DISPATCHER = "Lcom/kaldorgroup/pugpigbolt/net/analytics/AnalyticsDispatcher;"

/**
 * Turns off Firebase Analytics, Crashlytics, Sessions, Performance and advertising-ID collection
 * through the manifest switches. OneSignal (push) and Firebase Messaging are not touched.
 */
private val disableTelemetryManifestPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_NORDJYSKE)

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
                // Overwrite a stock entry in place (analytics and ad-ID are already declared),
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
 * Nordjyske is a PugPig Bolt app. Its analytics dispatcher sends every event (screen views, user
 * info, the track* helpers) to Firebase, Mixpanel, Facebook and the publisher's endpoint. Making
 * the three send methods return at once drops all of it at one choke point; the per-feature
 * track* helpers keep running but end in a no-op. The storefront, subscription and push flows are
 * untouched.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables analytics, crash reporting and session collection.",
) {
    compatibleWith(COMPATIBILITY_NORDJYSKE)

    dependsOn(disableTelemetryManifestPatch)

    execute {
        val dispatcher = mutableClassDefBy(DISPATCHER)
        val sendMethods = dispatcher.methods.filter {
            it.name in setOf("sendEvent", "setScreen", "setUserInfo") &&
                it.returnType == "V" && it.implementation != null
        }
        val sendEvents = sendMethods.count { it.name == "sendEvent" }
        val setScreens = sendMethods.count { it.name == "setScreen" }
        if (sendEvents < 2 || setScreens < 2) {
            throw PatchException(
                "$DISPATCHER has $sendEvents sendEvent and $setScreens setScreen overloads; " +
                    "expected at least 2 of each",
            )
        }
        sendMethods.forEach { it.returnEarly() }
    }
}
