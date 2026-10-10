package app.template.patches.librera.ads

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_LIBRERA

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Disables Librera's banner, interstitial and rewarded ads by reporting ads as " +
        "switched off and the reward window as open, so the Google Mobile Ads SDK never starts."
) {
    compatibleWith(COMPATIBILITY_LIBRERA)

    execute {
        IsShowAdsInAppFingerprint.method.returnEarly(false)
        IsRewardActivatedFingerprint.method.returnEarly(true)
    }
}
