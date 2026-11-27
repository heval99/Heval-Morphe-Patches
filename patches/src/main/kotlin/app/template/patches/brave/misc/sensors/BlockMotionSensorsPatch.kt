package app.template.patches.brave.misc.sensors

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL
import app.template.patches.shared.addInstructionChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PROVIDER = "Lorg/chromium/device/sensors/PlatformSensorProvider;"

@Suppress("unused")
val blockMotionSensorsPatch = bytecodePatch(
    name = "Block motion sensors",
    description = "Stops websites from reading the motion and orientation sensors.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    execute {
        // Chromium's Android sensor provider is a JNI class, so its names are kept.
        // 1. No sensor type is reported as available.
        Fingerprint(
            definingClass = PROVIDER,
            name = "hasSensorType",
            returnType = "Z",
            parameters = listOf("I"),
        ).method.returnEarly(false)

        // 2. The provider is built without a SensorManager, so no sensor can be created either.
        Fingerprint(
            definingClass = PROVIDER,
            name = "create",
            returnType = PROVIDER,
            parameters = emptyList(),
        ).method.apply {
            val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            if (returnIndex < 0) throw PatchException("PlatformSensorProvider.create has no return-object")
            val register = (instructions[returnIndex] as OneRegisterInstruction).registerA
            addInstructionChecked(
                returnIndex,
                "invoke-virtual {v$register}, $PROVIDER->setSensorManagerToNullForTesting()V",
            )
        }
    }
}
