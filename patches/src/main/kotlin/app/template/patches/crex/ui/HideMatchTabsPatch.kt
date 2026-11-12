package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val MATCH_TAB =
    "Lin/cricketexchange/app/cricketexchange/activities/LiveMatchActivity\$MatchTab;"
private const val TAB_LAYOUT_TAB = "Lcom/google/android/material/tabs/TabLayout\$Tab;"
private const val TAB_VIEW = "Lcom/google/android/material/tabs/TabLayout\$TabView;"
private const val LIST = "Ljava/util/List;"

// String resource bd() uses as the title of the last tab (MatchTab POINTS_TABLE) when the series
// is the bilateral kind: the "Series Stats" title. Any other series gets 0x7f140712 (Points Table).
// Checked in v26.10.07 from the code path only, see the note in the patch description.
private const val SERIES_STATS_TITLE = 0x7f14081d

private fun Method.instructionList() = implementation?.instructions?.toList().orEmpty()

// MatchTab constants have obfuscated field names (a..i); read the one for a given enum name from
// the enum's static initializer: `const-string vX, "NAME"` ... `sput-object vY, MatchTab->field`.
private fun Method.enumConstantField(name: String): String {
    val instructions = instructionList()
    val at = instructions.indexOfFirst {
        it.opcode == Opcode.CONST_STRING &&
            ((it as ReferenceInstruction).reference as StringReference).string == name
    }
    if (at < 0) throw PatchException("MatchTab constant $name not found")
    val put = instructions.drop(at).first { it.opcode == Opcode.SPUT_OBJECT } as ReferenceInstruction
    return (put.reference as FieldReference).name
}

@Suppress("unused")
val hideMatchTabsPatch = bytecodePatch(
    name = "Hide match tabs",
    description = "Phase 1.1: match screen keeps Info, Commentary, Scorecard and Points Table. " +
            "Fantasy and Graphs are removed from the tab list. The Live tab (Match Summary on " +
            "finished matches) and the Series Stats tab are kept in the pager but made invisible " +
            "and moved last, because the rest of the screen calls into their fragments without " +
            "null checks."
) {
    compatibleWith(COMPATIBILITY_CREX)

    execute {
        val activity = mutableClassDefBy(LIVE_MATCH_ACTIVITY)
        val tabs = mutableClassDefBy(MATCH_TAB)
        val enumInit = tabs.methods.singleOrNull { it.name == "<clinit>" }
            ?: throw PatchException("MatchTab static initializer not found")
        // MATCH_SUMMARY_OR_LIVE is the "Live" tab (or "Match Summary" once the match is over);
        // COMMENTARY is where the app is sent instead of the hidden tab.
        val live = enumInit.enumConstantField("MATCH_SUMMARY_OR_LIVE")
        val commentary = enumInit.enumConstantField("COMMENTARY")
        val fantasy = enumInit.enumConstantField("FANTASY")
        val graphs = enumInit.enumConstantField("ODDS_HISTORY")

        // 1. Ja() builds the tab list (a List<MatchTab> field, e2 in this version). Drop Fantasy
        //    and Graphs (their fragment calls are null-checked or wrapped in try/catch; the app
        //    already runs without Fantasy on accounts where it is off). Move Live to the end so
        //    swiping through the visible tabs never reaches it; it stays in the list, so its
        //    fragment is still created and the calls into it keep working.
        val listBuilder = activity.methods.singleOrNull { method ->
            method.name != "<init>" && method.returnType == "V" && method.parameterTypes.isEmpty() &&
                method.instructionList().let { instructions ->
                    instructions.any { it.opcode == Opcode.IPUT_OBJECT &&
                        ((it as ReferenceInstruction).reference as? FieldReference)?.let { ref ->
                            ref.definingClass == LIVE_MATCH_ACTIVITY && ref.type == LIST } == true } &&
                        instructions.any { it.opcode == Opcode.SGET_OBJECT &&
                            ((it as ReferenceInstruction).reference as? FieldReference)?.let { ref ->
                                ref.definingClass == MATCH_TAB && ref.name == live } == true } &&
                        instructions.count { it.opcode == Opcode.INVOKE_INTERFACE &&
                            ((it as ReferenceInstruction).reference as? MethodReference)?.let { ref ->
                                ref.definingClass == LIST && ref.name == "add" } == true } >= 6
                }
        } ?: throw PatchException("Match tab list builder not found")
        val tabList = listBuilder.instructionList().first {
            it.opcode == Opcode.IPUT_OBJECT &&
                ((it as ReferenceInstruction).reference as FieldReference).type == LIST
        }.let { (it as ReferenceInstruction).reference as FieldReference }
        val last = listBuilder.implementation!!.instructions.count() - 1
        if (listBuilder.getInstruction(last).opcode != Opcode.RETURN_VOID) {
            throw PatchException("Match tab list builder does not end in return-void")
        }
        // At the end v0 still holds the list and v1 is free.
        listBuilder.addInstructions(
            last,
            """
                sget-object v1, $MATCH_TAB->$live:$MATCH_TAB
                invoke-interface {v0, v1}, $LIST->remove(Ljava/lang/Object;)Z
                invoke-interface {v0, v1}, $LIST->add(Ljava/lang/Object;)Z
                sget-object v1, $MATCH_TAB->$fantasy:$MATCH_TAB
                invoke-interface {v0, v1}, $LIST->remove(Ljava/lang/Object;)Z
                sget-object v1, $MATCH_TAB->$graphs:$MATCH_TAB
                invoke-interface {v0, v1}, $LIST->remove(Ljava/lang/Object;)Z
            """.trimIndent()
        )

        // 2. Hf(MatchTab, boolean) is the only caller of ViewPager2.setCurrentItem; the app uses
        //    it to open a match on its Live tab. Send those requests to Commentary instead.
        val selectTab = activity.methods.singleOrNull { method ->
            method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf(MATCH_TAB, "Z") &&
                method.instructionList().any { instruction ->
                    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((instruction as ReferenceInstruction).reference as? MethodReference)?.name == "setCurrentItem"
                }
        } ?: throw PatchException("Match tab selector not found")
        // .locals 1: v0 is free at the first instruction.
        selectTab.addInstructions(
            0,
            """
                sget-object v0, $MATCH_TAB->$live:$MATCH_TAB
                if-ne p1, v0, :crex_not_live_tab
                sget-object p1, $MATCH_TAB->$commentary:$MATCH_TAB
                :crex_not_live_tab
                nop
            """.trimIndent()
        )

        // 3. bd(TabLayout.Tab, int) is the TabLayoutMediator strategy that titles each tab. The
        //    tab's view already exists there (the mediator adds it to the bar afterwards), so it
        //    can be hidden from inside bd. It runs again whenever the mediator rebuilds the tabs.
        val tabViewField = mutableClassDefBy(TAB_LAYOUT_TAB).fields.singleOrNull { it.type == TAB_VIEW }
            ?: throw PatchException("TabLayout.Tab view field not found")
        val configureTab = activity.methods.singleOrNull { method ->
            method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf(TAB_LAYOUT_TAB, "I")
        } ?: throw PatchException("Tab title strategy not found")
        // .locals 2: v0 and v1 are free where the code below goes; p1 = tab, p2 = position.
        val hideTab = """
            iget-object v0, p1, $TAB_LAYOUT_TAB->${tabViewField.name}:$TAB_VIEW
            const/16 v1, 0x8
            invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
        """.trimIndent()

        // 3a. Series Stats: the last tab is titled with SERIES_STATS_TITLE on its own branch
        //     (`const p2, 0x7f14081d`, reached by fall-through, no label). Hide it there. Points
        //     Table takes the other branch and stays visible. Inserted first: it sits later in
        //     the method, so the insert at index 0 below cannot shift it.
        val seriesStats = configureTab.instructionList().indexOfFirst {
            (it as? NarrowLiteralInstruction)?.narrowLiteral == SERIES_STATS_TITLE
        }
        if (seriesStats < 0) throw PatchException("Series Stats tab title not found")
        configureTab.addInstructions(seriesStats, hideTab)

        // 3b. Live: hide the tab at the Live position.
        configureTab.addInstructions(
            0,
            """
                iget-object v0, p0, $LIVE_MATCH_ACTIVITY->${tabList.name}:$LIST
                invoke-interface {v0, p2}, $LIST->get(I)Ljava/lang/Object;
                move-result-object v0
                sget-object v1, $MATCH_TAB->$live:$MATCH_TAB
                if-ne v0, v1, :crex_show_tab
                $hideTab
                :crex_show_tab
                nop
            """.trimIndent()
        )
    }
}
