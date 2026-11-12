package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val MATCH_INFO_DATA = "Lin/cricketexchange/app/cricketexchange/matchinfo/MatchInfoData;"
private const val LIVE_MATCH_ACTIVITY_TYPE = "Lin/cricketexchange/app/cricketexchange/activities/LiveMatchActivity;"

private const val WINNING_POLL_MODEL = "Lin/cricketexchange/app/cricketexchange/live/datamodels/WinningPollModel;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

// Title string of each Info tab section (res/values/public.xml, v26.10.07). The section's data
// field is the nearest `iget-object` + `if-eqz` before the title, in MatchInfoData.r(). When a
// section's data field has a type that other fields share, it is pinned with the field type.
private class TitledSection(val name: String, val titleId: Int, val fieldType: String? = null)

private val INFO_SECTIONS = listOf(
    TitledSection("Series Stats", 0x7f14081d),            // the "Series Stats (T20I)" header, link and card
    TitledSection("Team Comparison", 0x7f1408be),         // header above the Overall / On Venue table
    TitledSection("Avg Scores on Venue", 0x7f1400cd),     // over-by-over 1st / 2nd innings table
    TitledSection("Recent Matches on Venue", 0x7f140770), // recent matches list with the All Matches link
    TitledSection("Points Table", 0x7f140712, ARRAY_LIST), // points_table; the group rows are an ArrayList
)

@Suppress("unused")
val hideInfoSectionsPatch = bytecodePatch(
    name = "Hide Info sections",
    description = "Phase 1.1: removes the Who will win poll, Series Stats, Team Comparison, Avg " +
            "Scores on Venue, Recent Matches on Venue and Points Table sections from the match " +
            "screen's Info tab, using the app's own state for matches that have no data for " +
            "them. Venue Stats, weather, squads, umpires and the other sections are not touched."
) {
    compatibleWith(COMPATIBILITY_CREX)

    execute {
        // MatchInfoData.r(LiveMatchActivity) builds the Info tab's section list. Every section
        // starts with `iget-object v3, this, <data field>` / `if-eqz v3, :skip` (the label is on
        // the iget) and then adds a header titled with its string resource, followed by the data.
        // Making the register null before the check takes the app's existing "no data" branch.
        val builder = mutableClassDefBy(MATCH_INFO_DATA).methods.singleOrNull { method ->
            method.returnType == "Ljava/util/ArrayList;" &&
                method.parameterTypes.map { it.toString() } == listOf(LIVE_MATCH_ACTIVITY_TYPE)
        } ?: throw PatchException("Info tab section list builder not found")

        val instructions = builder.implementation!!.instructions.toList()

        // Null checks are `iget-object <data field>` / `if-eqz`, the label being on the iget.
        fun isCheck(i: Int, type: String?): Boolean {
            val instruction = instructions[i]
            if (instruction.opcode != Opcode.IGET_OBJECT || instructions[i + 1].opcode != Opcode.IF_EQZ) return false
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            return field.definingClass == MATCH_INFO_DATA && (type == null || field.type == type)
        }

        val inserts = INFO_SECTIONS.map { section ->
            val title = instructions.indexOfFirst {
                (it as? NarrowLiteralInstruction)?.narrowLiteral == section.titleId
            }
            if (title < 0) throw PatchException("${section.name} header title not found")
            val check = (title - 1 downTo 0).firstOrNull { isCheck(it, section.fieldType) }
                ?: throw PatchException("${section.name} null check not found")
            check + 1 to (instructions[check] as OneRegisterInstruction).registerA
        }.toMutableList()

        // Who will win poll: no title string of its own, so it is found by its data type, the
        // WinningPollModel field (u in this version).
        val poll = instructions.indices.firstOrNull { it + 1 < instructions.size && isCheck(it, WINNING_POLL_MODEL) }
            ?: throw PatchException("Who will win poll null check not found")
        inserts += poll + 1 to (instructions[poll] as OneRegisterInstruction).registerA

        // Inserted before each if-eqz (not a branch target). Highest index first so the
        // earlier insertions do not shift the later ones.
        inserts.sortedByDescending { it.first }.forEach { (index, register) ->
            builder.addInstructions(index, "const/4 v$register, 0x0")
        }
    }
}
