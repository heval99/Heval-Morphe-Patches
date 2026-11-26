package app.template.patches.wemuslim.layout

import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_WEMUSLIM
import app.template.patches.wemuslim.misc.encryption.bypassNativeEncryptionPatch

private const val DAILY_GOLDS_VIEW = "Lcom/fyxtech/muslim/worship/dailygolds/DailyGoldsView;"

// The "Ad-Free" pill at the top of the Home and Prayer screens (@id/daily_golds_view, a kept custom
// FrameLayout the layouts name). No branch ever hides it, and nothing is constrained to it. It is kept
// GONE like VipCardView. Cost: for paying members the same view carries the crown, the member-detail
// link and the Giving Box claim chip, which go too (the Giving Box stays reachable through the Me row).
@Suppress("unused")
val hideAdFreeButtonPatch = bytecodePatch(
    name = "Hide Ad-Free button",
    description = "Hides the Ad-Free button at the top of the Home and Prayer screens.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WEMUSLIM)

    dependsOn(bypassNativeEncryptionPatch)

    execute {
        forceViewGone(DAILY_GOLDS_VIEW, "Hide Ad-Free button")
    }
}
