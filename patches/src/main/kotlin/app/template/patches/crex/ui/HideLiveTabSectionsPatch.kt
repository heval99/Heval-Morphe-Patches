package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val LIVE_MATCH_FRAGMENT = "Lin/cricketexchange/app/cricketexchange/fragments/LiveMatchFragment;"

// Views inside the fragment's root (field t, v26.10.07).
private const val ID_QUIZ_TOP = 0x7f0a0f46
private const val ID_QUIZ = 0x7f0a0f47
private const val ID_GAMES_CONTAINER = 0x7f0a0eef
private const val ID_GAMES_LIST = 0x7f0a0ef2

private fun Method.strings() = implementation?.instructions?.mapNotNull {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
}.orEmpty()

private fun Method.literals() = implementation?.instructions?.mapNotNull {
    (it as? NarrowLiteralInstruction)?.narrowLiteral
}.orEmpty()

// Hides each view (looked up in the fragment's root view, field t) and returns. Registers v0/v1
// are free at the first instruction of the methods this is used on.
private fun hideViewsAndReturn(vararg ids: Int): String = ids.mapIndexed { i, id ->
    """
        iget-object v0, p0, $LIVE_MATCH_FRAGMENT->t:Landroid/view/View;
        if-eqz v0, :crex_skip_$i
        const v1, 0x${id.toString(16)}
        invoke-virtual {v0, v1}, Landroid/view/View;->findViewById(I)Landroid/view/View;
        move-result-object v0
        if-eqz v0, :crex_skip_$i
        const/16 v1, 0x8
        invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
        :crex_skip_$i
        nop
    """.trimIndent()
}.joinToString("\n") + "\nreturn-void"

@Suppress("unused")
val hideLiveTabSectionsPatch = bytecodePatch(
    name = "Hide Live tab sections",
    description = "Phase 1.1: removes the quiz, Latest Videos and Gaming Zone sections from the " +
            "match screen's Live tab (LiveMatchFragment). Top Headlines, weather and the other " +
            "sections are not touched."
) {
    compatibleWith(COMPATIBILITY_CREX)

    execute {
        val fragment = mutableClassDefBy(LIVE_MATCH_FRAGMENT)
        if (fragment.fields.none { it.name == "t" && it.type == "Landroid/view/View;" }) {
            throw PatchException("LiveMatchFragment root view field not found")
        }

        // 1. Latest Videos: L4(List) ("addVideos: ") builds the header, rows and All Videos button
        //    into the fragment's second PreMatchAdapter list. Doing nothing leaves that list as
        //    the app has it before any videos arrive.
        val videos = fragment.methods.singleOrNull { method ->
            method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf("Ljava/util/List;") &&
                method.strings().contains("addVideos: ")
        } ?: throw PatchException("Live tab videos method (addVideos) not found")
        videos.addInstructions(0, "return-void")

        // 2. Quiz: g8() ("setQuizView: ") adds the quiz card to the PreMatchAdapter (before the
        //    match) or shows the quiz layouts (during it). Hide those layouts and skip both.
        val quiz = fragment.methods.singleOrNull { method ->
            method.returnType == "V" && method.parameterTypes.isEmpty() &&
                method.strings().contains("setQuizView: ")
        } ?: throw PatchException("Live tab quiz method (setQuizView) not found")
        quiz.addInstructions(0, hideViewsAndReturn(ID_QUIZ_TOP, ID_QUIZ))

        // 3. Gaming Zone: D8() builds the game card carousel ("game_card") into the games list
        //    view. Hide the carousel and its container and skip the setup.
        val games = fragment.methods.singleOrNull { method ->
            method.returnType == "V" && method.parameterTypes.isEmpty() &&
                method.strings().contains("game_card") &&
                method.literals().contains(ID_GAMES_LIST)
        } ?: throw PatchException("Live tab Gaming Zone method not found")
        games.addInstructions(0, hideViewsAndReturn(ID_GAMES_CONTAINER, ID_GAMES_LIST))
    }
}
