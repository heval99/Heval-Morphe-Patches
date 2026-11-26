package app.template.patches.wemuslim.layout

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.template.patches.shared.addInstructionsChecked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Keeps one of the app's own custom views (a kept class the layout names, so R8 does not rename it)
 * GONE: the view hides itself at the end of every (Context, ...) constructor, and any later
 * setVisibility the screen makes is turned into GONE (a setVisibility override is added when the
 * class has none). Shared by "Hide premium banners" (VipCardView) and "Hide Me tab extras".
 */
internal fun BytecodePatchContext.forceViewGone(type: String, patchName: String, viaExtension: Boolean = false) {
    val view = mutableClassDefByOrNull(type) ?: throw PatchException("$patchName: $type not found")
    val superclass = view.superclass ?: throw PatchException("$patchName: $type has no superclass")

    val existing = view.methods.firstOrNull {
        it.name == "setVisibility" && it.parameterTypes.map(CharSequence::toString) == listOf("I") &&
            it.implementation != null
    }
    if (existing != null) {
        existing.addInstructionsChecked(0, "const/16 p1, 0x8")
    } else {
        view.methods.add(
            ImmutableMethod(
                type,
                "setVisibility",
                listOf(ImmutableMethodParameter("I", null, null)),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructionsChecked(
                    0,
                    """
                        const/16 p1, 0x8
                        invoke-super {p0, p1}, $superclass->setVisibility(I)V
                        return-void
                    """.trimIndent(),
                )
            },
        )
    }

    // Every constructor ends by hiding the view. p1 (the Context) is no longer read at a
    // return-void, so it carries the GONE value.
    val constructors = view.methods.filter {
        it.name == "<init>" && it.implementation != null &&
            it.parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;"
    }
    if (constructors.isEmpty()) throw PatchException("$patchName: $type has no (Context, ...) constructor")
    for (constructor in constructors) {
        // p0 sits below the parameter registers (J/D take two); the Context is p0 + 1.
        val parameterRegisters = constructor.parameterTypes.sumOf { if (it.toString() in setOf("J", "D")) 2 else 1 }
        val contextRegister = constructor.implementation!!.registerCount - parameterRegisters
        if (contextRegister > 15) {
            throw PatchException("$patchName: ${constructor.name} Context register v$contextRegister is out of invoke range")
        }
        // A constructor that reuses p0 for another value (MeMainBadgeCardView ends with p0 = a child
        // view) no longer holds the view at its return-void, so the view is hidden right after the
        // super/this constructor call instead, through the extension (no scratch register needed).
        val thisRegister = constructor.implementation!!.registerCount - parameterRegisters - 1
        val reusesThis = constructor.instructions.any {
            (it as? OneRegisterInstruction)?.registerA == thisRegister &&
                (it.opcode.setsRegister() || it.opcode.setsWideRegister())
        }
        if (reusesThis) {
            if (!viaExtension) {
                throw PatchException("$patchName: ${constructor.name} reuses p0, the view is not in it at return-void")
            }
            // The first <init> call whose receiver is p0 and that belongs to the superclass or the view.
            val initCall = constructor.instructions.indexOfFirst {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_DIRECT && ref != null && ref.name == "<init>" &&
                    (ref.definingClass == superclass || ref.definingClass == type) &&
                    (it as? FiveRegisterInstruction)?.registerC == thisRegister
            }
            if (initCall < 0) throw PatchException("$patchName: $type constructor has no <init> call")
            constructor.addInstructionsChecked(
                initCall + 1,
                "invoke-static {p0}, Lapp/template/extension/wemuslim/ViewHider;->gone(Landroid/view/View;)V",
            )
            continue
        }
        val returns = constructor.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
        if (returns.isEmpty()) throw PatchException("$patchName: $type constructor has no return-void")
        // Descending, so an insert never shifts an index still to be visited.
        for (index in returns.reversed()) {
            constructor.addInstructionsChecked(
                index,
                """
                    const/16 p1, 0x8
                    invoke-virtual {p0, p1}, $type->setVisibility(I)V
                """.trimIndent(),
            )
        }
    }
}
