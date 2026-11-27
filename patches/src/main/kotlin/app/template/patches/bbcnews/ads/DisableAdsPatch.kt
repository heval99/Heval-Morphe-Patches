package app.template.patches.bbcnews.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_BBCNEWS
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.misc.ads.neuterAdHooks

private const val ADS = "Lcom/google/android/gms/ads/"
private const val AD_MANAGER_AD_VIEW = "${ADS}admanager/AdManagerAdView;"
private const val RN = "Lio/invertase/googlemobileads/"
private const val FULL_SCREEN_MODULE = "${RN}ReactNativeGoogleMobileAdsFullScreenAdModule;"
private const val NATIVE_MODULE = "${RN}ReactNativeGoogleMobileAdsNativeModule;"

/**
 * BBC News is a React Native app; the ad slots are chosen in JS but every impression goes
 * through the Google Mobile Ads SDK via react-native-google-mobile-ads (io.invertase). Since
 * 2026.11.0 R8 renames and inlines the SDK: BaseAdView, AdView, AdLoader, InterstitialAd,
 * RewardedAd, RewardedInterstitialAd and AppOpenAd no longer exist by name. The kept anchors are
 * AdManagerAdView (its superclass is BaseAdView) and the two RN modules that drive every
 * full-screen and native load. There is no AppLovin MAX.
 *
 * 1. BaseAdView (the literal class on older builds, else the superclass of AdManagerAdView):
 *    every concrete void loadAd returns at once (RN banner, Prebid banner and mediation).
 * 2. AdManagerAdView.loadAd returns at once.
 * 3. The RN full-screen module's load(int, String, ReadableMap) reports an error event with code
 *    no-fill (the same path as its null-activity branch) so JS treats it as an unfilled request.
 * 4. The RN native module's load(String, ReadableMap, Promise) rejects the promise with no-fill.
 *
 * MobileAds.initialize is left alone: the RN module awaits it.
 */
@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Stops every Google Mobile Ads banner, native, interstitial, rewarded and app-open load.",
) {
    compatibleWith(COMPATIBILITY_BBCNEWS)

    execute {
        // 1 and 2. Banners.
        val bannerOwner = mutableClassDefByOrNull("${ADS}BaseAdView;")?.type
            ?: mutableClassDefBy(AD_MANAGER_AD_VIEW).superclass
            ?: throw PatchException("Disable ads: AdManagerAdView has no superclass")
        neuterAdHooks(
            mapOf(
                bannerOwner to setOf("loadAd"),
                AD_MANAGER_AD_VIEW to setOf("loadAd"),
            ),
        )

        // 3. Full-screen load: report no-fill. sendAdEvent is found by shape (private void,
        // String/int/String/WritableMap/WritableMap), not by name.
        val fullScreen = mutableClassDefBy(FULL_SCREEN_MODULE)
        val sendAdEvent = fullScreen.methods.singleOrNull {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(
                "Ljava/lang/String;", "I", "Ljava/lang/String;",
                "Lcom/facebook/react/bridge/WritableMap;", "Lcom/facebook/react/bridge/WritableMap;",
            ) && it.name != "access\$sendAdEvent" && it.implementation != null &&
                (it.accessFlags and 0x0008) == 0 // not static
        } ?: throw PatchException("Disable ads: sendAdEvent not found on $FULL_SCREEN_MODULE")
        val load = fullScreen.methods.singleOrNull {
            it.name == "load" && it.returnType == "V" && it.implementation != null &&
                it.parameterTypes.map(CharSequence::toString) ==
                listOf("I", "Ljava/lang/String;", "Lcom/facebook/react/bridge/ReadableMap;")
        } ?: throw PatchException("Disable ads: no concrete load(I,String,ReadableMap) on $FULL_SCREEN_MODULE")
        // Locals v0-v5 are free: the method declares 10 locals; v0 is reused after the map is built.
        load.addInstructionsChecked(
            0,
            """
                invoke-static {}, Lcom/facebook/react/bridge/Arguments;->createMap()Lcom/facebook/react/bridge/WritableMap;
                move-result-object v4
                const-string v0, "code"
                const-string v1, "no-fill"
                invoke-interface {v4, v0, v1}, Lcom/facebook/react/bridge/WritableMap;->putString(Ljava/lang/String;Ljava/lang/String;)V
                const-string v0, "message"
                const-string v1, "Ad request blocked."
                invoke-interface {v4, v0, v1}, Lcom/facebook/react/bridge/WritableMap;->putString(Ljava/lang/String;Ljava/lang/String;)V
                move-object v0, p0
                const-string v1, "error"
                move v2, p1
                move-object v3, p2
                const/4 v5, 0x0
                invoke-direct/range {v0 .. v5}, $FULL_SCREEN_MODULE->${sendAdEvent.name}(Ljava/lang/String;ILjava/lang/String;Lcom/facebook/react/bridge/WritableMap;Lcom/facebook/react/bridge/WritableMap;)V
                return-void
            """.trimIndent(),
        )

        // 4. Native ad load: reject the promise instead of leaving it unsettled.
        val nativeLoad = mutableClassDefBy(NATIVE_MODULE).methods.singleOrNull {
            it.name == "load" && it.returnType == "V" && it.implementation != null &&
                it.parameterTypes.map(CharSequence::toString) == listOf(
                "Ljava/lang/String;", "Lcom/facebook/react/bridge/ReadableMap;", "Lcom/facebook/react/bridge/Promise;",
            )
        } ?: throw PatchException("Disable ads: no concrete load(String,ReadableMap,Promise) on $NATIVE_MODULE")
        nativeLoad.addInstructionsChecked(
            0,
            """
                const-string v0, "no-fill"
                const-string v1, "Ad request blocked."
                invoke-interface {p3, v0, v1}, Lcom/facebook/react/bridge/Promise;->reject(Ljava/lang/String;Ljava/lang/String;)V
                return-void
            """.trimIndent(),
        )
    }
}
