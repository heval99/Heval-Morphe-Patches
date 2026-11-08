package app.template.patches.tv2nyheder.misc.telemetry

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_TV2NYHEDER
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * TV 2 Nyheder reports usage to Chartbeat and crashes to Bugsnag. Chartbeat is stopped at its
 * Tracker.setupTracker entry (both overloads), so the ping service, the foreground tracker and the
 * engagement timer never start. Bugsnag starts from its ContentProvider during application startup;
 * the one static start call in that onCreate is replaced with nop, so the provider still loads
 * cleanly but the SDK never initialises. TV 2's own backend calls and the ad stack are untouched.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables Chartbeat analytics and Bugsnag crash reporting.",
) {
    compatibleWith(COMPATIBILITY_TV2NYHEDER)

    execute {
        // 1. Chartbeat: both setupTracker overloads return at once.
        val tracker = mutableClassDefBy(CHARTBEAT_TRACKER)
        val setups = tracker.methods.filter {
            it.name == "setupTracker" && it.returnType == "V" && it.implementation != null
        }
        if (setups.isEmpty()) throw PatchException("Disable telemetry: no Chartbeat Tracker.setupTracker")
        setups.forEach { it.returnEarly() }

        // 2. Bugsnag: drop the provider's single start call.
        val provider = mutableClassDefBy(BUGSNAG_PROVIDER)
        val onCreate = provider.methods.singleOrNull {
            it.name == "onCreate" && it.returnType == "Z" && it.parameterTypes.isEmpty() &&
                it.implementation != null
        } ?: throw PatchException("Disable telemetry: $BUGSNAG_PROVIDER->onCreate()Z not found")
        val start = onCreate.implementation!!.instructions.withIndex().singleOrNull { (_, insn) ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            insn.opcode == Opcode.INVOKE_STATIC && ref != null && ref.returnType == "V" &&
                ref.parameterTypes.map(CharSequence::toString) == listOf("Landroid/app/Application;")
        } ?: throw PatchException("Disable telemetry: no Bugsnag start call in $BUGSNAG_PROVIDER->onCreate")
        onCreate.replaceInstruction(start.index, "nop")
    }
}
