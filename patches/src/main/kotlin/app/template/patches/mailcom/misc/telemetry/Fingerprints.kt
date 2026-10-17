package app.template.patches.mailcom.misc.telemetry

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.template.patches.mailcom.shared.toStringFingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// The Tracking2 event pipeline switch (9.18.3: remoteconfig.models.Tracking2Config), read by
// tracking2's EventCollector before it stores an event.
internal val Tracking2ConfigToStringFingerprint = toStringFingerprint("Tracking2Config(batchSize=")

// The per-tracker consent result (9.18.3: mail.tracking.TrackingPermissions), built only by
// TrackingModulePlugin.getTrackingPermissions.
internal val TrackingPermissionsToStringFingerprint =
    toStringFingerprint("TrackingPermissions(isBrainTrackingAllowed=")

// The app-monitoring switch (9.18.3: remoteconfig.models.AppMonConfig), read by MailAppMonProxy.
internal val AppMonConfigToStringFingerprint = toStringFingerprint("AppMonConfig(isAppMonEnabled=")

// The Sentry starter (9.18.3: trackingcrashes.sentry.SentryAPI.start(CrashTrackingConfig)): the
// one-parameter void that calls the SDK's SentryAndroid.init. The manifest sets
// io.sentry.auto-init=false, so this is the only way Sentry starts. The SDK's own init overloads
// and SentryInitProvider also call init; they are excluded by package and by being static.
internal object SentryStartFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "Lio/sentry/android/core/SentryAndroid;", name = "init"),
    ),
    custom = { method, classDef ->
        method.implementation != null && method.parameterTypes.size == 1 &&
            !AccessFlags.STATIC.isSet(method.accessFlags) && !classDef.type.startsWith("Lio/sentry/")
    },
)
