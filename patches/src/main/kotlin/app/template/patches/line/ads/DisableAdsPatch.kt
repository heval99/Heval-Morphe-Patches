// Based on andrewliang25/morphe-patches (GPL-3.0), patches/line/removeads/RemoveBannerAdsPatch.kt and
// patches/line/hideadviews/HideAdViewsPatch.kt: null banner responses, the LINE Ads view collapse
// and the Smart Channel host hide. The six Google AdManager wrapper hooks are not ported: their
// R8-renamed constructors changed shape in 26.15.1 and the reference skipped them silently.

package app.template.patches.line.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.template.patches.shared.Constants.COMPATIBILITY_LINE
import app.template.patches.shared.addInstructionsChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// Collapse the ad view itself: zero height, then GONE. The LINE Ads views never set their own
// visibility, so this sticks. Both onAttachedToWindow methods have .locals 5.
private const val HIDE_SELF = """
    invoke-virtual {p0}, Landroid/view/View;->getLayoutParams()Landroid/view/ViewGroup${'$'}LayoutParams;
    move-result-object v0
    if-eqz v0, :no_params
    const/4 v1, 0x0
    iput v1, v0, Landroid/view/ViewGroup${'$'}LayoutParams;->height:I
    invoke-virtual {p0, v0}, Landroid/view/View;->setLayoutParams(Landroid/view/ViewGroup${'$'}LayoutParams;)V
    :no_params
    const/16 v0, 0x8
    invoke-virtual {p0, v0}, Landroid/view/View;->setVisibility(I)V
"""

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes the Smart Channel banner above the chat list and hides LINE Ads " +
        "banners on Home, Wallet and other tabs.",
) {
    compatibleWith(COMPATIBILITY_LINE)

    execute {
        // 1. Smart Channel banners: the getBanners and getPrefetchableBanners receive branches
        //    return null right after their op-name string, before the Thrift read. The caller
        //    treats null as "no banner".
        listOf(
            GetBannersReceiveFingerprint to "getBanners",
            GetPrefetchableBannersReceiveFingerprint to "getPrefetchableBanners",
        ).forEach { (fingerprint, operation) ->
            fingerprint.method.apply {
                val nameIndex = fingerprint.instructionMatches.first().index
                val name = instructions[nameIndex]
                if (name.opcode != Opcode.CONST_STRING || name.getReference<StringReference>()?.string != operation) {
                    throw PatchException("$operation receive branch has no op-name string")
                }
                val register = (name as OneRegisterInstruction).registerA
                addInstructionsChecked(
                    nameIndex + 1,
                    """
                        const/4 v$register, 0x0
                        return-object v$register
                    """,
                )
            }
        }

        // 2. Chat list Smart Channel strip: hide its host FrameLayout (p1) when the controller is
        //    built. Touching a parameter before the super constructor call is allowed.
        SmartChannelControllerFingerprint.method.addInstructions(
            0,
            """
                const/16 v0, 0x8
                invoke-virtual {p1, v0}, Landroid/view/View;->setVisibility(I)V
            """,
        )

        // 3. LINE Ads SDK display ads (both view generations) collapse when attached.
        LadAdViewAttachFingerprint.method.addInstructions(0, HIDE_SELF)
        LyadAdViewAttachFingerprint.method.addInstructions(0, HIDE_SELF)
    }
}
