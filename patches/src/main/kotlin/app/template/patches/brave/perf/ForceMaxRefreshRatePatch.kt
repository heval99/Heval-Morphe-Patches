package app.template.patches.brave.perf

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL
import app.template.patches.shared.addInstructionsWithLabelsChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.ReferenceUtil

private const val DISPLAY_HELPER = "Lapp/template/extension/brave/DisplayHelper;"
private const val WINDOW_ANDROID = "Lorg/chromium/ui/base/WindowAndroid;"

// WindowAndroid.setPreferredRefreshRate(float) (r in 1.100.58): picks the display mode closest to the
// requested rate and logs "Refresh rate not supported : <rate>" when none fits. Kept class name.
internal object PreferredRefreshRateFingerprint : Fingerprint(
    definingClass = WINDOW_ANDROID,
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(string("Refresh rate not supported", StringComparisonType.STARTS_WITH)),
)

@Suppress("unused")
val forceMaxRefreshRatePatch = bytecodePatch(
    name = "Force max refresh rate",
    description = "Runs Brave at your screen's highest refresh rate.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    extendWith("extensions/brave.mpe")

    execute {
        val method = PreferredRefreshRateFingerprint.method
        val insns = method.instructions.toList()

        // The supported-modes list and the Window getter, read from the method's own code so no
        // obfuscated field or method name is written down here.
        val modes = insns.firstNotNullOfOrNull {
            val ref = (it as? ReferenceInstruction)?.reference
            if (it.opcode == Opcode.IGET_OBJECT && ref is FieldReference && ref.definingClass == WINDOW_ANDROID &&
                ref.type == "Ljava/util/ArrayList;"
            ) ref else null
        } ?: throw PatchException("${method.name}: the supported display modes list is gone")
        val windowGetter = insns.firstNotNullOfOrNull {
            val ref = (it as? ReferenceInstruction)?.reference
            if (it.opcode == Opcode.INVOKE_VIRTUAL && ref is MethodReference && ref.definingClass == WINDOW_ANDROID &&
                ref.returnType == "Landroid/view/Window;" && ref.parameterTypes.isEmpty()
            ) ref else null
        } ?: throw PatchException("${method.name}: the Window getter is gone")

        val locals = method.implementation!!.registerCount - method.parameterTypes.size - 1
        if (locals < 2) throw PatchException("${method.name}: needs two free local registers, has $locals")

        // Apply the best mode of the current resolution; fall through to Chromium's own code when the
        // helper finds none.
        method.addInstructionsWithLabelsChecked(
            0,
            """
                iget-object v0, p0, ${ReferenceUtil.getReferenceString(modes)}
                invoke-virtual {p0}, ${ReferenceUtil.getReferenceString(windowGetter)}
                move-result-object v1
                invoke-static {v1, v0}, $DISPLAY_HELPER->applyMaxRefreshRate(Landroid/view/Window;Ljava/util/List;)Z
                move-result v0
                if-eqz v0, :stock
                return-void
                :stock
                nop
            """,
        )
    }
}
