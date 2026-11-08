package app.template.patches.information.misc.telemetry

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_INFORMATION
import app.template.patches.shared.Constants.COMPATIBILITY_WEEKENDAVISEN
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.w3c.dom.Element

// Every Visiolink reader tracker extends this shared base: FirebaseTracker,
// GoogleTagManagerTracker and the app-specific MatomoTracker/TealiumTracker. It is the stable
// anchor across the Visiolink newspaper apps.
private const val ABSTRACT_TRACKER = "Lcom/visiolink/reader/base/tracking/AbstractTracker;"

/**
 * Turns off Firebase Analytics/Crashlytics/Sessions/Performance and ad-ID collection through the
 * manifest switches.
 */
private val disableTelemetryManifestPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_INFORMATION, COMPATIBILITY_WEEKENDAVISEN)

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
 * Information and Weekendavisen are Visiolink readers. All of its analytics send through tracker classes that extend
 * the shared `AbstractTracker` (Firebase, Google Tag Manager, Matomo and Tealium implementations);
 * every event is a void `track...` method on one of those subclasses. The patch no-ops every void
 * method on every concrete subclass, which drops all four trackers at once while leaving their
 * getters and the rest of the reader untouched. Firebase automatic collection is switched off at
 * the manifest. The subscription/e-paper content is server-side; ads are separate.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables analytics trackers.",
) {
    compatibleWith(COMPATIBILITY_INFORMATION, COMPATIBILITY_WEEKENDAVISEN)

    dependsOn(disableTelemetryManifestPatch)

    execute {
        val trackers = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (classDef.type == ABSTRACT_TRACKER) return@classDefForEach
            if (AccessFlags.ABSTRACT.isSet(classDef.accessFlags)) return@classDefForEach
            var type = classDef.superclass
            var steps = 0
            while (type != null && steps < 8) {
                if (type == ABSTRACT_TRACKER) {
                    trackers += classDef
                    return@classDefForEach
                }
                type = classDefByOrNull(type)?.superclass
                steps++
            }
        }
        if (trackers.size < 3) {
            throw PatchException(
                "only ${trackers.size} $ABSTRACT_TRACKER subclasses found: ${trackers.map { it.type }}",
            )
        }

        var hooked = 0
        trackers.forEach { classDef ->
            mutableClassDefBy(classDef).methods
                .filter {
                    it.returnType == "V" && it.implementation != null &&
                        it.name != "<init>" && it.name != "<clinit>"
                }
                .forEach {
                    it.returnEarly()
                    hooked++
                }
        }
        if (hooked < 10) {
            throw PatchException("only $hooked tracking methods on ${trackers.map { it.type }}")
        }
    }
}
