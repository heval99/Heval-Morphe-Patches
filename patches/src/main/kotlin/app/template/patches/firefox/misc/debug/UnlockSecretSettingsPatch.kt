package app.template.patches.firefox.misc.debug

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.template.patches.firefox.shared.concreteMethod
import app.template.patches.firefox.shared.fieldRef
import app.template.patches.firefox.shared.insns
import app.template.patches.firefox.shared.registers
import app.template.patches.firefox.shared.settingsGetter
import app.template.patches.firefox.shared.smali
import app.template.patches.shared.Constants.COMPATIBILITY_FIREFOX
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.requireNoBranchTargetAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val FEATURE_FLAGS = "Lorg/mozilla/fenix/FeatureFlags;"
internal const val CUSTOM_EXTENSION_COLLECTION = "$FEATURE_FLAGS->customExtensionCollectionFeature:Z"

@Suppress("unused")
val unlockSecretSettingsPatch = bytecodePatch(
    name = "Unlock secret settings",
    description = "Unlocks the hidden debug settings.",
) {
    compatibleWith(COMPATIBILITY_FIREFOX)

    execute {
        // ── 1. The debug menu reads as revealed in every session ────────────────────────────
        settingsGetter("getShowSecretDebugMenuThisSession").returnEarly(true)

        // ── 2. The custom add-on collection works on release ────────────────────────────────
        //  FeatureFlags.<clinit> stores "Nightly, Beta or debug" into customExtensionCollectionFeature;
        //  right after that store, true is stored over it. The store's register is reused, so
        //  the next instruction naming it must write it (158.0: v1, never named again; 161.0: v1,
        //  rewritten by the move-result of the next isDebug() call).
        val clinit = concreteMethod(FEATURE_FLAGS, "<clinit>", emptyList())
        val insns = clinit.insns()
        val stores = insns.indices.filter {
            insns[it].opcode == Opcode.SPUT_BOOLEAN && insns[it].fieldRef()?.smali() == CUSTOM_EXTENSION_COLLECTION
        }
        val store = stores.singleOrNull()
            ?: throw PatchException("$FEATURE_FLAGS.<clinit> stores customExtensionCollectionFeature ${stores.size} times, expected 1")
        val register = (insns[store] as OneRegisterInstruction).registerA
        // Straight-line code after the store: the first instruction naming the register must
        // overwrite it (a move-result or const), otherwise the forced value could be read.
        val after = insns.drop(store + 1)
        val firstUse = after.indexOfFirst { register in it.registers() }
        if (firstUse >= 0) {
            val straight = after.take(firstUse).none {
                it.opcode.name.startsWith("IF_") || it.opcode.name.startsWith("GOTO") ||
                    it.opcode.name.contains("SWITCH") || it.opcode.name.startsWith("RETURN")
            }
            val use = after[firstUse]
            val overwrites = use.opcode == Opcode.MOVE_RESULT || use.opcode == Opcode.MOVE_RESULT_OBJECT ||
                use.opcode == Opcode.CONST_4 || use.opcode == Opcode.CONST_16 || use.opcode == Opcode.CONST
            if (!straight || !overwrites) {
                throw PatchException("$FEATURE_FLAGS.<clinit> uses v$register after the customExtensionCollectionFeature store")
            }
        }
        clinit.requireNoBranchTargetAt(store + 1, "forcing customExtensionCollectionFeature")
        clinit.addInstructionsChecked(
            store + 1,
            """
                const/16 v$register, 0x1
                sput-boolean v$register, $CUSTOM_EXTENSION_COLLECTION
            """,
        )
    }
}
