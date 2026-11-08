package app.template.patches.information.misc.telemetry

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_INFORMATION
import app.template.patches.shared.Constants.COMPATIBILITY_WEEKENDAVISEN
import app.template.patches.shared.misc.telemetry.forceFirebaseManifestSwitches
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef

// Every Visiolink reader tracker extends this shared base: FirebaseTracker,
// GoogleTagManagerTracker and the app-specific MatomoTracker/TealiumTracker. It is the stable
// anchor across the Visiolink newspaper apps.
private const val ABSTRACT_TRACKER = "Lcom/visiolink/reader/base/tracking/AbstractTracker;"

/**
 * Information and Weekendavisen are Visiolink readers. All of its analytics send through tracker classes that extend
 * the shared `AbstractTracker` (Firebase, Google Tag Manager, Matomo and Tealium implementations);
 * every event is a void `track...` method on one of those subclasses. The patch no-ops every void
 * method on every concrete subclass, which drops all four trackers at once while leaving their
 * getters and the rest of the reader untouched. Firebase Analytics' automatic collection and its
 * ad-ID collection are switched off where the SDK reads its manifest switches (both apps bundle
 * no Crashlytics, Sessions or Performance). The subscription/e-paper content is server-side; ads
 * are separate.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables analytics trackers.",
) {
    compatibleWith(COMPATIBILITY_INFORMATION, COMPATIBILITY_WEEKENDAVISEN)

    execute {
        // Firebase Analytics and ad-ID collection off, as the manifest switches would set them
        // but in the bytecode (no resource re-encode, rule 12). Information and Weekendavisen
        // bundle no Crashlytics, Sessions or Performance.
        forceFirebaseManifestSwitches(
            app = "Visiolink reader",
            analytics = true,
            crashlytics = false,
            sessions = false,
            performance = false,
        )

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
