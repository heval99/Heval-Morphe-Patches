// Ported from AmpleReVanced/revanced-patches (GPL-3.0), layout/tab/RemoveShopTabPatch.kt, written
// for KakaoTalk 26.7.2. Changes for 26.8.3: Ample deletes the whole slot-3 map.put; in 26.8.3 that
// slot is SHOPPING_TAB or, for accounts without shopping, CALL_TAB, so only SHOPPING_TAB is
// dropped now. The slot-4 key rewrite is not needed (the keys are discarded) and the edit fails
// loudly when the slot-3 pattern is missing.

package app.template.patches.kakaotalk.layout

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.template.patches.shared.Constants.COMPATIBILITY_KAKAOTALK
import app.template.patches.shared.addInstructionsWithLabelsChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SHOP_SLOT = 3

@Suppress("unused")
val removeShopTabPatch = bytecodePatch(
    name = "Remove shop tab",
    description = "Removes the shopping tab from the bottom navigation bar.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_KAKAOTALK)

    execute {
        if (mutableClassDefBy(TAB_INFO).fields.none { it.name == "SHOPPING_TAB" }) {
            throw PatchException("$TAB_INFO has no SHOPPING_TAB")
        }

        AddNavigationTabFingerprint.method.apply {
            // Slot 3: const/4 vS, 0x3; Integer.valueOf(vS) -> vK; resolve(vS, config) -> vT;
            // map.put(vK, vT).
            val slot = instructions.indices.firstOrNull { i ->
                val const = instructions[i]
                const.opcode == Opcode.CONST_4 && (const as NarrowLiteralInstruction).narrowLiteral == SHOP_SLOT &&
                    instructions.getOrNull(i + 1)?.getReference<MethodReference>()?.name == "valueOf" &&
                    instructions.getOrNull(i + 2)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
                    instructions.getOrNull(i + 3)?.getReference<MethodReference>()?.returnType == TAB_INFO &&
                    instructions.getOrNull(i + 4)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
                    instructions.getOrNull(i + 5)?.getReference<MethodReference>()?.name == "put"
            } ?: throw PatchException("No slot-3 tab map.put in $definingClass->$name")
            val key = (instructions[slot + 2] as OneRegisterInstruction).registerA
            val tab = (instructions[slot + 4] as OneRegisterInstruction).registerA

            // Before the put: a SHOPPING_TAB result becomes null, which the final filterNotNull
            // drops. The key register is borrowed for the comparison and then rebuilt.
            addInstructionsWithLabelsChecked(
                slot + 5,
                """
                    sget-object v$key, $TAB_INFO->SHOPPING_TAB:$TAB_INFO
                    if-ne v$tab, v$key, :keep_tab
                    const/4 v$tab, 0x0
                    :keep_tab
                    const/16 v$key, $SHOP_SLOT
                    invoke-static/range {v$key .. v$key}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                    move-result-object v$key
                """,
            )
        }
    }
}
