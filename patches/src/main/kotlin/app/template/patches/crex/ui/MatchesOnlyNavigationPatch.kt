package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION = "Lapp/template/extension/crexnav/CrexNav;"

@Suppress("unused")
val matchesOnlyNavigationPatch = bytecodePatch(
    name = "Matches only (hide bottom navigation)",
    description = "UI only. Hides the whole bottom navigation bar so the app stays on Matches, " +
            "with the Live, For you, Upcoming and Finished tabs at the top (swipe or tap). The " +
            "Menu screen is reached through the top-right three-line button. The bar's items " +
            "are hidden, not removed, because HomeActivity looks them up by index."
) {
    compatibleWith(COMPATIBILITY_CREX)

    extendWith("extensions/crexnav.mpe")

    execute {
        val fingerprint = HomeCreateFingerprint
        val method = fingerprint.method
        val getMenuIndex = fingerprint.instructionMatches.first().index
        val stringIndex = fingerprint.instructionMatches.last().index

        // 1. Launch tab. The restore block ends at a label followed by
        //    `sget-object vX, HomeTabs->VIDEOS`; v0 holds the tab name there. Overwrite it with
        //    "MATCHES" right after that sget (inserting after, not at, the labelled instruction
        //    so the branches into the label still run it). Done first: it sits later in the
        //    method, so the insert below cannot shift its index.
        var videosSget = -1
        for (i in stringIndex downTo 0) {
            val instruction = method.getInstruction(i)
            if (instruction.opcode != Opcode.SGET_OBJECT) continue
            val ref = (instruction as ReferenceInstruction).reference as FieldReference
            if (ref.definingClass == HOME_TABS && ref.name == "VIDEOS") {
                videosSget = i
                break
            }
        }
        if (videosSget < 0) throw PatchException("HomeTabs.VIDEOS check before \"Default Videos\" not found")
        method.addInstructions(videosSget + 1, "const-string v0, \"MATCHES\"")

        // 2. Hide the bar. getMenu() is `invoke-virtual {bar}`; right before it the bar's register
        //    holds the view, so hand it to the extension (no scratch register needed; the app
        //    reuses that register for the Menu right after).
        val getMenu = method.getInstruction(getMenuIndex)
        if (getMenu !is FiveRegisterInstruction) {
            throw PatchException("Expected getMenu() to be an invoke instruction")
        }
        method.addInstructions(
            getMenuIndex,
            "invoke-static {v${getMenu.registerC}}, $EXTENSION->hide(Landroid/view/View;)V"
        )
    }
}
