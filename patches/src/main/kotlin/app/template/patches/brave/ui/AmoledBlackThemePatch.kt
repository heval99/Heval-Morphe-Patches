package app.template.patches.brave.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import app.template.patches.shared.addInstructionsChecked
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val AMOLED = "Lapp/template/extension/brave/AmoledColors;"

// The theme color resolver (g7b.g in 1.100.58): static (Context, TypedValue)I that returns the
// referenced color resource or the raw value. The Material and Chromium color lookups by attribute
// (getColor(Context, attr, default) and the throwing variant) both end in it.
internal object ThemeColorResolverFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("L", "Landroid/util/TypedValue;"),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation?.instructions?.toList().orEmpty().any {
            val ref = (it as? ReferenceInstruction)?.reference
            ref is MethodReference && ref.definingClass == "Landroid/content/Context;" && ref.name == "getColor"
        }
    },
)

@Suppress("unused")
val amoledBlackThemePatch = bytecodePatch(
    name = "AMOLED black theme",
    description = "Turns the dark theme's grey backgrounds pure black.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    extendWith("extensions/brave.mpe")

    execute {
        // Remap the color the resolver returns (dark mode only, decided by the extension from the
        // Context's night mode). Colors are resolved by name, not by the obfuscated resource table,
        // so this is code only; no resource is changed.
        val resolver = ThemeColorResolverFingerprint.method
        val returns = resolver.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }
        if (returns.isEmpty()) throw PatchException("${resolver.name}: no return")
        // The stock code reuses the Context register for the int color, so the Context cannot be passed to the
        // return hook: the night mode is read from it on entry (a thread-local flag, no Context is kept) and
        // the return hook only gets the color register.
        returns.asReversed().forEach { (index, insn) ->
            val color = (insn as OneRegisterInstruction).registerA
            resolver.addInstructionsAtControlFlowLabelChecked(
                index,
                """
                    invoke-static {v$color}, $AMOLED->remap(I)I
                    move-result v$color
                """,
            )
        }
        resolver.addInstructionsChecked(
            0,
            "invoke-static {p0}, $AMOLED->enter(Landroid/content/Context;)V",
        )
    }
}
