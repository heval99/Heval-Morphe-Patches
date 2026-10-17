package app.template.patches.sofascore.ads

import app.morphe.patcher.Fingerprint

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
