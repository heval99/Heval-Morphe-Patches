package app.template.patches.sofascore.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_SOFASCORE
import app.template.patches.sofascore.shared.forceCallResult
import app.template.patches.sofascore.shared.modalPredicate

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Disables banner, interstitial, feed, native, preroll and rewarded ads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SOFASCORE)

    execute {
        // Flip the local account flags the app's ad logic consults, so every
        // "should show ads" decision evaluates to false on its own:
        //   if (getForceAds()) showAds();                       // -> false
        //   if (!getHasServerAds() || getPurchasedAds()
        //       || getForceHideAds() || ...) hideAds();         // -> true
        GetForceAdsFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )

        GetForceHideAdsFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )

        GetHasServerAdsFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """
        )

        // With getHasServerAds() false every user looks ad-free, so the one-time "Active
        // subscription" upgrade sheet (shown to subscribers after a plan change) would open for
        // everyone. Its queue predicate is "!hasServerAds || purchasedAds || PR_XAIST set";
        // reading hasServerAds as true there restores the stock answer for non-subscribers, and
        // the sheet is never queued (no queue item waits for its dismiss). Real purchases still
        // see it. Kept here because this patch causes it.
        modalPredicate(ACTIVE_SUBSCRIPTION_MODAL, ACTIVE_SUBSCRIPTION_PREDICATE_ANCHOR)
            .forceCallResult("UserAccount.getHasServerAds()", value = true) {
                it.definingClass == USER_ACCOUNT_CLASS && it.name == "getHasServerAds"
            }
    }
}
