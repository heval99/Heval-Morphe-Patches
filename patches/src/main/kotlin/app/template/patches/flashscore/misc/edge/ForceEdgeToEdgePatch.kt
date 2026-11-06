package app.template.patches.flashscore.misc.edge

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_FLASHSCORE
import app.template.patches.shared.addInstructionsChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val WINDOW = "Landroid/view/Window;"

/** `invoke-virtual Window.setNavigationBarColor(I)V` on the window register. */
private fun ReferenceInstruction.isSetNavigationBarColor(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val call = reference as? MethodReference ?: return false
    return call.definingClass == WINDOW && call.name == "setNavigationBarColor" && call.returnType == "V" &&
        call.parameterTypes.map(CharSequence::toString) == listOf("I")
}

/**
 * Flashscore paints the navigation bar with the Material edge-to-edge helper (one method per API
 * level, each setting the status bar colour, the navigation bar colour and then the bar icon
 * appearance). On the dark screens the painted bar does not match the content, so the gesture pill
 * sits on a bright strip.
 *
 * The patch finds every method that sets both bar colours (the three helpers) and forces the
 * navigation bar colour argument to transparent, leaving the status bar and the icon appearance
 * untouched. The app already lays out edge-to-edge (targetSdk 35), so the content and the pill then
 * share one background.
 */
@Suppress("unused")
val forceEdgeToEdgePatch = bytecodePatch(
    name = "Force edge-to-edge",
    description = "Makes the navigation bar transparent so the content blends into it.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_FLASHSCORE)

    execute {
        // Collect first: mutating classes while iterating them is not allowed.
        val owners = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            val hasNavigation = classDef.methods.any { method ->
                method.implementation?.instructions?.any { insn ->
                    insn is ReferenceInstruction && insn.isSetNavigationBarColor()
                } == true
            }
            val hasStatus = classDef.methods.any { method ->
                method.implementation?.instructions?.any { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == WINDOW && ref.name == "setStatusBarColor"
                } == true
            }
            if (hasNavigation && hasStatus) owners += classDef
        }
        if (owners.isEmpty()) {
            throw PatchException("Force edge-to-edge: no method sets both system bar colours")
        }

        var forced = 0
        for (owner in owners) {
            for (method in mutableClassDefBy(owner).methods) {
                val insns = method.implementation?.instructions?.toList() ?: continue
                // Back to front, so the earlier indices stay valid.
                insns.withIndex()
                    .filter { (_, insn) -> insn is ReferenceInstruction && insn.isSetNavigationBarColor() }
                    .map { (index, insn) -> index to insn as ReferenceInstruction }
                    .reversed()
                    .forEach { (index, call) ->
                        val colour = (call as FiveRegisterInstruction).registerD
                        method.addInstructionsChecked(index, "const/16 v$colour, 0x0")
                        forced++
                    }
            }
        }
        if (forced == 0) throw PatchException("Force edge-to-edge: no setNavigationBarColor call was rewritten")
    }
}
