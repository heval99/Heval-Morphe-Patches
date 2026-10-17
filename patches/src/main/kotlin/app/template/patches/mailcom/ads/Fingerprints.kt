package app.template.patches.mailcom.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.template.patches.mailcom.shared.STRING
import app.template.patches.mailcom.shared.toStringFingerprint

// The Cocos remote-config ad switches (9.18.3: remoteconfig.models.AdvertisingIdentityConfig).
// AdsFeatureFlagsManager maps each getter into the flow its ad placement reads.
internal val AdvertisingIdentityConfigToStringFingerprint =
    toStringFingerprint("AdvertisingIdentityConfig(isId5Enabled=")

// The interstitial switch (9.18.3: remoteconfig.models.InterstitialAdConfig), read by
// InterstitialFeatureFlagsManager.isInterstitialEnabled.
internal val InterstitialAdConfigToStringFingerprint =
    toStringFingerprint("InterstitialAdConfig(isEnabled=")

// The ad pixel sender (9.18.3: tracking.adtracker.AdTracker.send). CommonTracker.trackCustomUrl
// is its only caller, and every trackCustomUrl caller reports an ad event: inbox ad displayed,
// read, deleted and swiped away, smart header ad, ad measure points and shopping-list order ads.
internal object AdTrackerSendFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(STRING),
    filters = listOf(
        string("AdTracker: url is null or invalid", StringComparisonType.STARTS_WITH),
        string("AdTracker failed", StringComparisonType.STARTS_WITH),
    ),
    custom = { method, _ -> method.implementation != null },
)
