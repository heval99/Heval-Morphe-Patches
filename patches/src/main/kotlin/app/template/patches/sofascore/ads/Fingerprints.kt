package app.template.patches.sofascore.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

internal const val USER_ACCOUNT_CLASS = "Lcom/sofascore/local_persistance/UserAccount;"

// The modal queue's display name of the one-time "Active subscription" upgrade sheet
// (ActiveSubscriptionBottomSheet), and a preference key its eligibility predicate reads (the
// only use of the key in the shared lambda class). See sofascore/shared/ModalQueue.kt.
internal const val ACTIVE_SUBSCRIPTION_MODAL = "Active subscription"
internal const val ACTIVE_SUBSCRIPTION_PREDICATE_ANCHOR = "PR_XAIST"

/**
 * UserAccount is not obfuscated, so these getters are stable anchors.
 * Every ad decision in the app is derived from these account flags.
 */
object GetForceAdsFingerprint : Fingerprint(
    definingClass = USER_ACCOUNT_CLASS,
    name = "getForceAds",
)

object GetForceHideAdsFingerprint : Fingerprint(
    definingClass = USER_ACCOUNT_CLASS,
    name = "getForceHideAds",
)

object GetHasServerAdsFingerprint : Fingerprint(
    definingClass = USER_ACCOUNT_CLASS,
    name = "getHasServerAds",
)

/**
 * Ad SDK content providers the manifest starts in every process before Application.onCreate
 * (2026-11-03, 26.10.19). Their class names are kept because the manifest names them. Each
 * onCreate()Z only registers the SDK's activity/process lifecycle tracking (IronSource, LevelPlay,
 * Mintegral, Vungle), which nothing needs once the ad SDKs are never started. PicassoProvider is
 * deliberately not here: its onCreate only stores the context, and Picasso.get() throws without it.
 */
internal val AD_SDK_LIFECYCLE_PROVIDERS = listOf(
    "Lcom/ironsource/lifecycle/IronsourceLifecycleProvider;",
    "Lcom/ironsource/lifecycle/LevelPlayActivityLifecycleProvider;",
    "Lcom/mbridge/msdk/config/component/status/MBComponentLifecycleProvider;",
    "Lcom/vungle/ads/VungleProvider;",
)

/**
 * Unity Ads' androidx.startup Initializer (a meta-data entry of InitializationProvider, so the
 * class name is kept). Its create(Context) stores the application and starts Unity's start-up
 * coroutine; the Unity SDK itself is only started by the ad start-up thread Disable ads removes.
 */
internal const val UNITY_ADS_INITIALIZER = "Lcom/unity3d/services/core/configuration/AdsSdkInitializer;"

internal const val RELEASE_APP_CLASS = "Lcom/sofascore/results/ReleaseApp;"

/**
 * The ad SDK start-up: a method that sets the ad SDKs' consent (Vungle's setGDPRStatus) and ends
 * with `new Thread(new <runnable>(context, 1)).start()`; that thread sets the remaining consent and
 * starts Mintegral, Unity, Moloco, Pangle, Equativ, Nimbus, AppHarbr and MobileAds.initialize.
 * It sat in ReleaseApp's consent method up to 26.11.09; since 26.11.16 R8 moved it into the
 * coroutine that method launches (invokeSuspend), so the anchor is the library calls
 * (VunglePrivacySettings.setGDPRStatus, called from this one method, plus Thread.start) and not a
 * class or return type.
 */
object AdSdkStartupFingerprint : Fingerprint(
    filters = listOf(
        methodCall(definingClass = "Lcom/vungle/ads/VunglePrivacySettings;", name = "setGDPRStatus"),
        methodCall(definingClass = "Ljava/lang/Thread;", name = "start"),
    ),
)
