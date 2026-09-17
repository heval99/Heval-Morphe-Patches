package app.template.patches.sofascore.misc.telemetry

import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_SOFASCORE
import app.morphe.util.returnEarly

// FirebaseCrashlytics is R8-renamed in this build (the public class no longer exists),
// so it is located at patch time by the SharedPreferences key its settings class uses.
private const val CRASHLYTICS_COLLECTION_KEY = "firebase_crashlytics_collection_enabled"

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables AppsFlyer and Firebase Analytics event logging, plus Crashlytics crash reporting."
) {
    compatibleWith(COMPATIBILITY_SOFASCORE)

    execute {
        // AppsFlyer
        AppsFlyerLogEventFingerprint.methodOrNull?.returnEarly()

        // Firebase Analytics / Google Measurement
        GmsMeasurementLogEventFingerprint.methodOrNull?.returnEarly()

        // Crashlytics: two classes reference the collection-enabled pref key - the
        // settings holder (boolean getter that decides whether anything is collected)
        // and the FirebaseCrashlytics instance itself (log/recordException methods).
        // Disable collection and neuter the non-fatal reporting entry points.
        for (classDef in classDefByStrings(CRASHLYTICS_COLLECTION_KEY)) {
            val crashlytics = mutableClassDefBy(classDef)
            crashlytics.methods
                .filter { it.returnType == "Z" && it.parameterTypes.isEmpty() }
                .forEach { it.returnEarly(false) }
            crashlytics.methods
                .filter {
                    it.returnType == "V" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes.first().toString() == "Ljava/lang/Throwable;"
                }
                .forEach { it.returnEarly() }
        }
    }
}
