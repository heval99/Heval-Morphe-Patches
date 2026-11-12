package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX
import com.android.tools.smali.dexlib2.Opcode

private const val HOME_LIVE_TAB_FRAGMENT =
    "Lin/cricketexchange/app/cricketexchange/matches/HomeLiveTabFragmentNew;"
private const val HORIZONTAL_VIDEOS_LIST_DATA =
    "Lin/cricketexchange/app/cricketexchange/videos/data/HorizontalVideosListData;"

@Suppress("unused")
val hideMatchesExtrasPatch = bytecodePatch(
    name = "Hide Matches extras",
    description = "Phase 1.1: removes the Trending Videos block (and its All Videos button) from " +
            "the Matches Live tab, and the red NEW label and red dot over the bottom navigation."
) {
    compatibleWith(COMPATIBILITY_CREX)

    execute {
        // 1. Trending Videos on the Live tab. LiveCardsAdapter adds the videos group only when
        //    HomeLiveTabFragmentNew.T1() (a bridge getter for the HorizontalVideosListData field)
        //    returns non-null: getGroupCount() counts it and getGroupType() returns 4 for it. The
        //    app already runs without it before the videos load, so null is a normal state.
        val liveTab = mutableClassDefBy(HOME_LIVE_TAB_FRAGMENT)
        val videosGetter = liveTab.methods.singleOrNull { method ->
            method.returnType == HORIZONTAL_VIDEOS_LIST_DATA &&
                method.parameterTypes.map { it.toString() } == listOf(HOME_LIVE_TAB_FRAGMENT)
        } ?: throw PatchException("Live tab videos getter not found")
        videosGetter.addInstructions(
            0,
            """
                const/4 p0, 0x0
                return-object p0
            """.trimIndent()
        )

        // 2. Red "NEW" label: oc() only fills in the intro time pref and animates the label in.
        NewBadgeFingerprint.method.addInstructions(0, "return-void")

        // 3. Red dot: Eb(Boolean) ends with `setVisibility(v1)` on the dot (v0). Force it hidden
        //    right before the method returns (v1 is free there).
        val dot = RedDotFingerprint.method
        val last = dot.implementation!!.instructions.count() - 1
        if (dot.getInstruction(last).opcode != Opcode.RETURN_VOID) {
            throw PatchException("Red dot method does not end in return-void")
        }
        dot.addInstructions(
            last,
            """
                const/16 v1, 0x8
                invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
            """.trimIndent()
        )
    }
}
