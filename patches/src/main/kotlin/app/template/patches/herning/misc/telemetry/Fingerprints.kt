package app.template.patches.herning.misc.telemetry

import app.morphe.patcher.Fingerprint

// Herning Folkeblad is a Textalk/Prenly reader (se.textalk.media.reader). That reader library keeps
// its class and method names - the APK is not R8-obfuscated outside the bundled third-party SDKs -
// and every analytics event funnels into one dispatcher:
//   - the app's log*/send* helpers build an event and call it,
//   - after a consent change the queued events are replayed through it,
//   - when the reporters are not ready yet it queues the event instead of sending it.
// The method is located by its shape plus the class's static "reporters" list, so a rename of the
// surrounding class or of the lambda type does not break the anchor.
internal object TextalkAnalyticsDispatchFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef ->
        method.name == "invokeReporterWithCrashlyticsWrapper" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes.single().toString() != "Ljava/lang/String;" &&
            method.implementation != null &&
            classDef.fields.any { it.name == "reporters" && it.type == "Ljava/util/List;" }
    },
)
