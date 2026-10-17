package app.template.patches.football433.ads

import app.morphe.patcher.Fingerprint

internal const val METHOD_CALL = "Lio/flutter/plugin/common/MethodCall;"
internal const val METHOD_RESULT = "Lio/flutter/plugin/common/MethodChannel\$Result;"

// The google_mobile_ads plugin's Java side is R8-renamed (2.3.548: package P8), so every anchor
// below is a string the plugin sends over its method channel or a method shape.

// The load methods Dart can call on "plugins.flutter.io/google_mobile_ads". Each case creates
// one FlutterAd, tracks it in the AdInstanceManager and calls its load method.
internal val LOAD_METHOD_NAMES = listOf(
    "loadBannerAd",
    "loadAdManagerBannerAd",
    "loadFluidAd",
    "loadNativeAd",
    "loadInterstitialAd",
    "loadAdManagerInterstitialAd",
    "loadRewardedAd",
    "loadRewardedInterstitialAd",
    "loadAppOpenAd",
)

// GoogleMobileAdsPlugin.onMethodCall (2.3.548: P8/N), the switch over every channel method.
internal object PluginMethodCallFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(METHOD_CALL, METHOD_RESULT),
    strings = LOAD_METHOD_NAMES + "MobileAds#initialize",
    custom = { method, _ -> method.implementation != null },
)

// AdInstanceManager.onAdFailedToLoad(int adId, FlutterLoadAdError) (2.3.548: P8/a.k). It posts
// the onAdFailedToLoad event with the error to Dart on the main looper.
internal object AdFailedToLoadFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("adId", "eventName", "onAdFailedToLoad", "loadAdError"),
    custom = { method, _ ->
        method.implementation != null && method.parameterTypes.size == 2 &&
            method.parameterTypes[0].toString() == "I"
    },
)

// AdInstanceManager.trackAd(FlutterAd, int adId) (2.3.548: P8/a.x); its first parameter is the
// abstract FlutterAd base class (2.3.548: P8/f, holding the int adId).
internal object TrackAdFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Ad for following adId already exists: %d"),
    custom = { method, _ ->
        method.implementation != null && method.parameterTypes.size == 2 &&
            method.parameterTypes[1].toString() == "I"
    },
)
