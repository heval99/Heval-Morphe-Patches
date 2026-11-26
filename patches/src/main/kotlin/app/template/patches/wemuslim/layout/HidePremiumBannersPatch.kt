package app.template.patches.wemuslim.layout

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_WEMUSLIM
import app.template.patches.wemuslim.misc.encryption.bypassNativeEncryptionPatch
import app.template.patches.shared.addInstructionsChecked

private const val INFO_STREAM_UNIT = "Lcom/fyxtech/muslim/protobuf/InfoStreamProto\$InfoStreamUnit;"
private const val VIP_CARD_VIEW = "Lcom/fyxtech/muslim/about/module/main/VipCardView;"
private const val DISCOUNT_POPUP_RES =
    "Lcom/fyxtech/muslim/protobuf/LocationResourceProto\$GetMembershipDiscountPopupRes;"

// The two membership upsells outside ad slots. The "Join Membership to remove ads" placeholders,
// the "remove ads" chips and the ignore-ad dialog live inside the ad views (libads layouts), so
// Disable ads already keeps them from being inflated. Member badges next to user names
// (imgPremium, image_vip) are identity marks, not upsells, and are kept. The points store's
// member-price rows and the subscription page itself are left alone.
@Suppress("unused")
val hidePremiumBannersPatch = bytecodePatch(
    name = "Hide premium banners",
    description = "Hides the membership banners and pop-ups.",
) {
    compatibleWith(COMPATIBILITY_WEMUSLIM)

    dependsOn(bypassNativeEncryptionPatch)

    execute {
        // 0. The membership discount pop-up (#135, the "Wemu Premium member" free-trial offer): the
        // server's GetMembershipDiscountPopupRes (also carried in the app-startup event) lists the
        // pop-ups to show, and both readers take getInfosList(). An empty list means no pop-up.
        val popupRes = mutableClassDefByOrNull(DISCOUNT_POPUP_RES)
            ?: throw PatchException("Hide premium banners: $DISCOUNT_POPUP_RES not found")
        val infos = popupRes.methods.singleOrNull {
            it.name == "getInfosList" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" &&
                it.implementation != null
        } ?: throw PatchException("Hide premium banners: no $DISCOUNT_POPUP_RES->getInfosList()")
        // The getter is `iget-object p0, p0, ...; return-object p0`: p0 is free to carry the list.
        infos.addInstructionsChecked(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object p0
                return-object p0
            """.trimIndent(),
        )

        // 1. Home feed: the server sends the membership offer card ("Join Membership", "Enjoy an
        // ad-free experience", discount price and countdown; MembershipGuideSubViewHolder) as an
        // InfoStreamUnit with its membership_info field set. The stream builder checks the
        // protobuf accessor in a chain of has* branches and drops a unit that matches none, so
        // answering false removes the card without a gap. Nothing else reads the accessor.
        val streamUnit = mutableClassDefByOrNull(INFO_STREAM_UNIT)
            ?: throw PatchException("Hide premium banners: $INFO_STREAM_UNIT not found")
        streamUnit.methods.singleOrNull {
            it.name == "hasMembershipInfo" && it.returnType == "Z" &&
                it.parameterTypes.isEmpty() && it.implementation != null
        }?.returnEarly(false)
            ?: throw PatchException("Hide premium banners: no $INFO_STREAM_UNIT->hasMembershipInfo()Z")

        // 2. Me screen: the membership card (VipCardView, a custom FrameLayout the layout names,
        // so R8 keeps it) is GONE from construction on, and any later setVisibility the screen
        // makes is turned into GONE. The rest of the header and the menu below are untouched.
        forceViewGone(VIP_CARD_VIEW, "Hide premium banners")
    }
}
