package app.template.patches.wunderground.premium

import app.morphe.patcher.Fingerprint

// Weather Underground ships the Adobe Airlock SDK, which evaluates the "ads.Ad Free"
// entitlement locally from the cached purchase info. These getters are the gates the UI and
// ad code consult; all three classes are unobfuscated.
object AdFreePurchasedFingerprint : Fingerprint(
    definingClass = "Lcom/wunderground/android/weather/utils/AirlockValueUtil;",
    name = "isAdFreePurchased",
)

object IsAdsFreePurchasedFingerprint : Fingerprint(
    definingClass = "Lcom/wunderground/android/weather/app/inapp/PremiumHelper;",
    name = "isAdsFreePurchased",
)

object IsAdsFreeV2UserFingerprint : Fingerprint(
    definingClass = "Lcom/wunderground/android/weather/app/WUApplication;",
    name = "isAdsFreeV2User",
)

// FeatureManager reads the raw Airlock feature "ads.Ad Free" (not the getters above) to decide
// whether to start the ad SDKs (AdsManager, AdSlotsConfigurationManager, Amazon preloader). Airlock
// recalculates that feature on every launch from the billing state, so without this gate the ads
// return after a few opens.
object InitAdManagersFingerprint : Fingerprint(
    definingClass = "Lcom/wunderground/android/weather/app/features/FeatureManager;",
    name = "initAdManagers",
)
