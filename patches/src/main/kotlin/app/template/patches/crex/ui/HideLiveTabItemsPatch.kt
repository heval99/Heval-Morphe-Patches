package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val WINNING_POLL_MODEL_TYPE = "Lin/cricketexchange/app/cricketexchange/live/datamodels/WinningPollModel;"
private const val WEATHER_OBJECT = "Lin/cricketexchange/app/cricketexchange/live/datamodels/WeatherObject;"
private const val HEAD_TO_HEAD_DATA = "Lin/cricketexchange/app/cricketexchange/live/datamodels/HeadToHeadData;"
private const val JSON_OBJECT = "Lorg/json/JSONObject;"

@Suppress("unused")
val hideLiveTabItemsPatch = bytecodePatch(
    name = "Hide Live tab poll, weather and head to head",
    description = "Phase 1.1: removes the Who will win poll, the Weather Conditions card and the " +
            "Head to Head row from the match screen's Live tab (the pre-match list). The preview " +
            "video and Top Headlines are not touched."
) {
    compatibleWith(COMPATIBILITY_CREX)

    execute {
        // LiveMatchActivity.Jb() ("getPreMatchReportList: ") builds the Live tab's list. Each item
        // is added behind a null check on a data field; storing null in the register right after
        // the field is read takes the app's own "no data" branch:
        //  - WinningPollModel field: its null check falls through to a log-only path, so the
        //    poll is never added.
        //  - WeatherObject field: the weather goes into MatchPreviewData(news, video, weather);
        //    with null weather the card keeps the preview video / headlines, or is skipped when
        //    it has neither.
        //  - head to head: guarded by a null check on the match JSON before HeadToHeadData is built.
        val builder = mutableClassDefBy(LIVE_MATCH_ACTIVITY).methods.singleOrNull { method ->
            method.returnType == "Ljava/util/ArrayList;" && method.parameterTypes.isEmpty() &&
                method.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "getPreMatchReportList: "
                } == true
        } ?: throw PatchException("Live tab list builder (getPreMatchReportList) not found")

        val instructions = builder.implementation!!.instructions.toList()

        fun firstGetOf(type: String): Int = instructions.indexOfFirst {
            it.opcode == Opcode.IGET_OBJECT &&
                ((it as ReferenceInstruction).reference as FieldReference).type == type
        }

        val poll = firstGetOf(WINNING_POLL_MODEL_TYPE)
        if (poll < 0) throw PatchException("Poll field read not found")
        val weather = firstGetOf(WEATHER_OBJECT)
        if (weather < 0) throw PatchException("Weather field read not found")

        val headToHead = instructions.indexOfFirst {
            it.opcode == Opcode.NEW_INSTANCE &&
                ((it as ReferenceInstruction).reference as TypeReference).type == HEAD_TO_HEAD_DATA
        }
        if (headToHead < 0) throw PatchException("HeadToHeadData creation not found")
        // Nearest match JSON read (iget-object JSONObject, then if-eqz) before it.
        val headToHeadCheck = (headToHead - 1 downTo 0).firstOrNull { i ->
            instructions[i].opcode == Opcode.IGET_OBJECT &&
                ((instructions[i] as ReferenceInstruction).reference as FieldReference).type == JSON_OBJECT &&
                instructions[i + 1].opcode == Opcode.IF_EQZ
        } ?: throw PatchException("Head to Head null check not found")

        // After each read (labels sit on the read itself). Highest index first.
        listOf(poll, weather, headToHeadCheck).sortedDescending().forEach { index ->
            val register = (instructions[index] as OneRegisterInstruction).registerA
            builder.addInstructions(index + 1, "const/4 v$register, 0x0")
        }
    }
}
