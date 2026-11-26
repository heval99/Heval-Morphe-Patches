package app.template.patches.wemuslim.layout

import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_WEMUSLIM
import app.template.patches.wemuslim.misc.encryption.bypassNativeEncryptionPatch

private val ME_TAB_EXTRA_VIEWS = listOf(
    // @id/friendsNumberView: the friends / followers counters.
    "Lcom/fyxtech/muslim/about/module/main/FriendsNumberView;",
    // @id/vBadgeCard: the badge card.
    "Lcom/fyxtech/muslim/about/point/card/MeMainBadgeCardView;",
    // @id/importantFuncView: the shortcut cards.
    "Lcom/fyxtech/muslim/about/module/main/ImportantFuncView;",
)

// The three custom views me_fragment_main.xml names above the menu list (kept classes, so R8 does not
// rename them). They are kept GONE the same way "Hide premium banners" keeps the membership card
// (VipCardView) GONE, except MeMainBadgeCardView, whose constructor ends with p0 reused for a child
// view: it is hidden right after its super constructor call through extensions/wemuslim ViewHider.
// The settings row and the rest of the menu list (a RecyclerView), the login
// header, the prayer reminder check and the FAQ are untouched.
@Suppress("unused")
val hideMeTabExtrasPatch = bytecodePatch(
    name = "Hide Me tab extras",
    description = "Hides the friends, badge and shortcut cards on the Me tab.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WEMUSLIM)

    dependsOn(bypassNativeEncryptionPatch)

    // MeMainBadgeCardView's constructor reuses p0, so it is hidden through ViewHider.gone instead.
    extendWith("extensions/wemuslim.mpe")

    execute {
        for (view in ME_TAB_EXTRA_VIEWS) forceViewGone(view, "Hide Me tab extras", viaExtension = true)
    }
}
