package app.template.patches.bizcal.ads

import app.morphe.patcher.Fingerprint

// The one user-level ad gate. AdsUtil.showAdsForUser(Context) is consulted before the mediation
// SDKs are initialized (MainActivity.onCreate -> StoreUtil.initializeMobileAds) and before any
// interstitial is preloaded or shown. Forcing it false keeps the ad SDKs from starting and stops the
// interstitial path. The app's own code is readable, so the names are stable.
object ShowAdsForUserFingerprint : Fingerprint(
    definingClass = "Lcom/appgenix/bizcal/util/AdsUtil;",
    name = "showAdsForUser",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
)
