package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val hideMatchDiscussionsPatch = bytecodePatch(
    name = "Hide match Discussions",
    description = "Phase 1.1: removes the Discussions tab and the floating Discuss button from the " +
            "match screen by switching off the flag the app already uses when discussions are " +
            "disabled for a match."
) {
    compatibleWith(COMPATIBILITY_CREX)

    execute {
        val fingerprint = MatchDiscussionsFlagFingerprint
        val method = fingerprint.method
        val prefIndex = fingerprint.instructionMatches.first().index

        // onCreate stores the "discussions enabled" result with the first boolean field write
        // after the pref read (`iput-boolean v7, p0, ...->c2:Z`). Ja() builds the tab list from
        // that field and Da() shows the Discuss button from it, so storing false right after
        // gives the app's own "discussions off" state. The extra store goes after the original
        // one, whose instruction carries a branch label.
        var store = -1
        for (i in prefIndex until method.implementation!!.instructions.count()) {
            if (method.getInstruction(i).opcode == Opcode.IPUT_BOOLEAN) {
                store = i
                break
            }
        }
        if (store < 0) throw PatchException("Discussions flag store not found after the pref read")
        val instruction = method.getInstruction(store)
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        if (field.definingClass != LIVE_MATCH_ACTIVITY || field.type != "Z") {
            throw PatchException("Unexpected field store after the discussions pref: $field")
        }
        // The value register is overwritten by the next instructions, so it is free here.
        val value = (instruction as OneRegisterInstruction).registerA
        method.addInstructions(
            store + 1,
            """
                const/4 v$value, 0x0
                iput-boolean v$value, p0, ${field.definingClass}->${field.name}:${field.type}
            """.trimIndent()
        )
    }
}
