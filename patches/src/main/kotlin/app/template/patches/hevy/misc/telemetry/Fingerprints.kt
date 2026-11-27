package app.template.patches.hevy.misc.telemetry

import app.morphe.patcher.Fingerprint

// Since 3.2.2 R8 renames the Amplitude and Branch SDK classes, so these are found by
// stable strings (public intent constants and reflection names), never by class name.

// Branch SDK constructor (Branch(Context)): loads the tracking-state flag from the public
// "bnc_tracking_state" preference key into the tracking-state object.
internal object BranchTrackingStateFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("bnc_tracking_state"),
    custom = { method, _ -> method.name == "<init>" && method.parameterTypes.size == 1 },
)

// Amplitude AndroidContextProvider.CachedInfo constructor. Since 3.2.7 R8 inlines both ad-ID readers
// (Google reflection and the Fire OS settings path) into it; the first thing it does is read the
// context provider's "advertising ID enabled" flag (iget-boolean) and skip both when it is false.
internal object AmplitudeCachedInfoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(
        "getAdvertisingIdInfo", "isLimitAdTrackingEnabled", "limit_ad_tracking", "advertising_id",
    ),
    custom = { method, _ -> method.name == "<init>" },
)
