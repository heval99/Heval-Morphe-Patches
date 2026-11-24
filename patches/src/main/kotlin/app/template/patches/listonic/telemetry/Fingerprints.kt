package app.template.patches.listonic.telemetry

import app.morphe.patcher.Fingerprint

private const val TIKTOK = "Lcom/l/utils/ads/TikTokReporterImpl;"
private const val ADSHIFT = "Lcom/l/utils/ads/AdShiftManager;"
private const val CLARITY = "Lcom/microsoft/clarity/Clarity;"
private const val GEMIUS = "Lcom/gemius/sdk/GemiusSdk;"
private const val AUDIENCE_EVENT = "Lcom/gemius/sdk/audience/AudienceEvent;"
private const val FACEBOOK = "Lcom/facebook/FacebookSdk;"

private fun concrete(definingClass: String, name: String, returnType: String, parameters: List<String>? = null) =
    Fingerprint(
        definingClass = definingClass,
        name = name,
        returnType = returnType,
        parameters = parameters,
        custom = { method, _ -> method.implementation != null },
    )

// The app's own TikTok Business SDK wrapper (class and method names kept by R8): SDK init, the
// start-tracking step, event reporting and consent. All concrete voids.
internal val tikTokStart = concrete(TIKTOK, "start", "V", listOf("Z"))
internal val tikTokBeginTracking = concrete(TIKTOK, "beginTracking", "V", listOf())
internal val tikTokTrack = concrete(TIKTOK, "track", "V", listOf("L"))
internal val tikTokSetConsent = concrete(TIKTOK, "setConsent", "V", listOf("L"))

// The app's AdShift (attribution) wrapper, also kept by name. handleAppLinkIntent only forwards to
// AdShiftLib inside a try/catch, and the deep-link callback passed to initialize is the app's
// no-op default, so nothing needs the SDK's answer except the device id (null is handled).
internal val adShiftInitialize = concrete(ADSHIFT, "initialize", "V", listOf("Landroid/content/Context;", "L", "L"))
internal val adShiftStart = concrete(ADSHIFT, "start", "V", listOf())
internal val adShiftStop = concrete(ADSHIFT, "stop", "V", listOf())
internal val adShiftHandleAppLinkIntent = concrete(ADSHIFT, "handleAppLinkIntent", "V", listOf("Landroid/content/Intent;", "Landroid/os/Bundle;"))
internal val adShiftDeviceId = concrete(ADSHIFT, "getAdShiftDeviceId", "Ljava/lang/String;", listOf())

// Microsoft Clarity library statics (public API, unobfuscated).
internal val clarityInitializeContext = concrete(CLARITY, "initialize", "Ljava/lang/Boolean;", listOf("Landroid/content/Context;", "L"))
internal val clarityInitializeActivity = concrete(CLARITY, "initialize", "Ljava/lang/Boolean;", listOf("Landroid/app/Activity;", "L"))
internal val claritySendCustomEvent = concrete(CLARITY, "sendCustomEvent", "Z", listOf("Ljava/lang/String;"))
internal val claritySetCurrentScreenName = concrete(CLARITY, "setCurrentScreenName", "Ljava/lang/Boolean;", listOf("Ljava/lang/String;"))

// Gemius audience SDK library API.
internal val gemiusInit = concrete(GEMIUS, "init", "V", listOf("Landroid/content/Context;"))
internal val gemiusSendEvent = concrete(AUDIENCE_EVENT, "sendEvent", "V", listOf())

// Facebook SDK, kept only for login: its app-event auto logging and advertiser id collection flags.
internal val facebookAutoLogEvents = concrete(FACEBOOK, "getAutoLogAppEventsEnabled", "Z", listOf())
internal val facebookAdvertiserId = concrete(FACEBOOK, "getAdvertiserIDCollectionEnabled", "Z", listOf())
