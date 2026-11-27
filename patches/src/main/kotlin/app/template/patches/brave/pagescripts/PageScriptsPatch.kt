package app.template.patches.brave.pagescripts

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.brave.userscripts.ApplicationOnCreateFingerprint
import app.template.patches.brave.userscripts.PageLoadFinishedFingerprint
import app.template.patches.brave.userscripts.TAB_IMPL_TYPE
import app.template.patches.brave.userscripts.TAB_TYPE
import app.template.patches.brave.userscripts.GURL_TYPE
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.requireNoBranchTargetAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/template/extension/brave/PageScripts;"

/**
 * The shared page-loaded hook of the built-in page scripts: hands every finished primary-frame page
 * load to `PageScripts.onPageLoad(Tab)`, which does nothing until a script patch enables its script.
 * It uses the same load callback and TabImpl register derivation as "Userscripts" (the register that
 * the tab observer dispatch passes as the Tab is read, not overwritten), so both can be applied together.
 */
internal val pageScriptsHookPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    extendWith("extensions/brave.mpe")

    execute {
        val load = PageLoadFinishedFingerprint.method
        val insns = load.instructions.toList()
        val dispatch = insns.indexOfFirst {
            val ref = (it as? ReferenceInstruction)?.reference
            it.opcode == Opcode.INVOKE_INTERFACE && ref is MethodReference && ref.returnType == "V" &&
                ref.parameterTypes.map(CharSequence::toString) == listOf(TAB_TYPE, GURL_TYPE)
        }
        if (dispatch < 0) throw PatchException("${load.name}: the TabObserver dispatch is gone")
        val tabRegister = (insns[dispatch] as FiveRegisterInstruction).registerD
        val tabLoad = (0 until dispatch).lastOrNull {
            val ref = (insns[it] as? ReferenceInstruction)?.reference
            insns[it].opcode == Opcode.IGET_OBJECT && ref is FieldReference && ref.type == TAB_IMPL_TYPE &&
                (insns[it] as TwoRegisterInstruction).registerA == tabRegister
        } ?: throw PatchException("${load.name}: nothing loads the TabImpl into v$tabRegister before the dispatch")
        if (tabRegister >= 16) throw PatchException("${load.name}: the tab register v$tabRegister does not fit")
        load.requireNoBranchTargetAt(tabLoad + 1, "page-loaded hook")
        load.addInstructionsChecked(
            tabLoad + 1,
            "invoke-static {v$tabRegister}, $EXTENSION->onPageLoad(Ljava/lang/Object;)V",
        )
    }
}

private fun pageScriptPatch(name: String, description: String, enableMethod: String) = bytecodePatch(
    name = name,
    description = description,
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    dependsOn(pageScriptsHookPatch)

    execute {
        // Switch the script on when the app starts, before any tab loads a page.
        ApplicationOnCreateFingerprint.method.addInstructionsChecked(
            0,
            "invoke-static {}, $EXTENSION->$enableMethod()V",
        )
    }
}

@Suppress("unused")
val forceCopyPastePatch = pageScriptPatch(
    name = "Force copy and paste",
    description = "Lets you select, copy and paste text on pages that block it.",
    enableMethod = "enableCopyPaste",
)

@Suppress("unused")
val forcePictureInPicturePatch = pageScriptPatch(
    name = "Force picture-in-picture",
    description = "Allows picture-in-picture on videos that disable it.",
    enableMethod = "enablePictureInPicture",
)
