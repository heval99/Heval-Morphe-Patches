package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

// Group types of the "For you" list, from HomeCardsAdapter.getGroupView (v26.10.07):
//   5 = "Featured Series" header (+ cards)   10 = "More" header (rankings, stats corner)
//   7 = "Gaming Zone / Play Games" header (+ games row)   6 = "What are you looking for" search
// Kept on purpose: 14 ("More series" button), 4 (videos), 9 (ad banner slot) and the series rows.
private val REMOVED_GROUP_TYPES = listOf(5, 10, 7, 6)

@Suppress("unused")
val hideForYouSectionsPatch = bytecodePatch(
    name = "Hide For you extras",
    description = "Removes the Featured Series, More, Play Games and bottom search sections " +
            "from the For you tab. The series and match list, the Follow card and the Fixtures " +
            "button are not touched."
) {
    compatibleWith(COMPATIBILITY_CREX)

    execute {
        val fingerprint = ForYouGroupListFingerprint
        val method = fingerprint.method

        // The group-type list field is loaded just before List.clear(); read it from there so
        // its (obfuscated) name is never hard-coded.
        val clearIndex = fingerprint.instructionMatches.first().index
        val load = method.getInstruction(clearIndex - 1)
        val listField = (load as? ReferenceInstruction)?.reference as? FieldReference
        if (load.opcode != Opcode.IGET_OBJECT || listField?.type != "Ljava/util/List;") {
            throw PatchException("Expected the group-type list load before clear(), found ${load.opcode}")
        }

        // J() ends with `return-void` after appending the fixed sections (v0-v6 are free there).
        // Remove the unwanted types so no group, header or child row is ever created for them.
        // Each type appears at most once and List.remove(Object) drops the first match.
        // Adding for commit
        val last = method.implementation!!.instructions.count() - 1
        if (method.getInstruction(last).opcode != Opcode.RETURN_VOID) {
            throw PatchException("Group list builder does not end in return-void")
        }
        val drop = REMOVED_GROUP_TYPES.joinToString("\n") { type ->
            """
                iget-object v0, p0, ${listField.definingClass}->${listField.name}:${listField.type}
                const/16 v1, $type
                invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v1
                invoke-interface {v0, v1}, Ljava/util/List;->remove(Ljava/lang/Object;)Z
            """.trimIndent()
        }
        method.addInstructions(last, drop)
    }
}
