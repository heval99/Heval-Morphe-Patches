package app.template.patches.librera.ads

import app.morphe.patcher.Fingerprint

// Librera's ad gate. AppsConfig.isShowAdsInApp(Context) is consulted before MobileAds is
// initialized (LibreraApp.onCreate) and before the activity, main tab and banner ad paths run.
// Returns false when Google Play services or the device is unsuitable, and the ads setting
// otherwise. Forcing false keeps the Google Mobile Ads SDK from starting.
object IsShowAdsInAppFingerprint : Fingerprint(
    definingClass = "Lcom/foobnix/pdf/info/AppsConfig;",
    name = "isShowAdsInApp",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
)

// ADS.isRewardActivated() reports whether the reward window (after a rewarded video) is still
// open. The interstitial loader, the interstitial show path, the banner show path and the
// reward view binder all read it, and they skip ads while it returns true.
object IsRewardActivatedFingerprint : Fingerprint(
    definingClass = "Lcom/foobnix/pdf/info/ADS;",
    name = "isRewardActivated",
    returnType = "Z",
    parameters = emptyList(),
)
