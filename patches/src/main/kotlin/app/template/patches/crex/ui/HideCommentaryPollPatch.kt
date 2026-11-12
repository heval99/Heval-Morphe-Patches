package app.template.patches.crex.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_CREX

private const val COMMENTARY_ADAPTER = "Lin/cricketexchange/app/cricketexchange/commentaryv2/ui/CommentaryAdapterV2;"
private const val POLLS_VIEW_HOLDER =
    "Lin/cricketexchange/app/cricketexchange/stories/viewholder/components/PollsViewHolder;"
private const val RECYCLER_VIEW_HOLDER = "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;"
private const val POLLS_ADAPTER = "Lin/cricketexchange/app/cricketexchange/polls/PollsView\$PollsAdapter;"

@Suppress("unused")
val hideCommentaryPollPatch = bytecodePatch(
    name = "Hide Commentary poll",
    description = "Phase 1.1: collapses the poll cards (Who will win ...) in the Commentary tab. " +
            "The rows are bound with a PollsViewHolder; they are hidden and given zero height " +
            "instead of being bound, and the paths that would otherwise touch the never-bound " +
            "poll (live payload updates and the poll's inner list) are guarded."
) {
    compatibleWith(COMPATIBILITY_CREX)

    execute {
        // onBindViewHolder(holder, position) has .locals 3, so v0-v2 are free at its first
        // instruction. Only poll rows use PollsViewHolder (view holders are recycled per view
        // type), so a collapsed holder is never reused for another kind of row.
        val bind = mutableClassDefBy(COMMENTARY_ADAPTER).methods.singleOrNull { method ->
            method.name == "onBindViewHolder" && method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf(RECYCLER_VIEW_HOLDER, "I")
        } ?: throw PatchException("Commentary adapter onBindViewHolder not found")

        bind.addInstructions(
            0,
            """
                instance-of v0, p1, $POLLS_VIEW_HOLDER
                if-eqz v0, :crex_not_poll
                iget-object v0, p1, $RECYCLER_VIEW_HOLDER->itemView:Landroid/view/View;
                const/16 v1, 0x8
                invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
                invoke-virtual {v0}, Landroid/view/View;->getLayoutParams()Landroid/view/ViewGroup${'$'}LayoutParams;
                move-result-object v1
                if-eqz v1, :crex_poll_done
                const/4 v2, 0x0
                iput v2, v1, Landroid/view/ViewGroup${'$'}LayoutParams;->height:I
                invoke-virtual {v0, v1}, Landroid/view/View;->setLayoutParams(Landroid/view/ViewGroup${'$'}LayoutParams;)V
                :crex_poll_done
                return-void
                :crex_not_poll
                nop
            """.trimIndent()
        )

        // Skipping the bind above leaves PollsViewHolder.h (the previous PollData) null. The
        // app's payload overload onBindViewHolder(holder, position, payloads) then reaches
        // PollsViewHolder.n(Component) on live updates, which reads h.d() -> NullPointerException
        // (seen only on matches whose commentary carries a poll row, e.g. a live WBBL match).
        // That overload has .locals 2, so v0 is free at its first instruction.
        val payloadBind = mutableClassDefBy(COMMENTARY_ADAPTER).methods.singleOrNull { method ->
            method.name == "onBindViewHolder" && method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } ==
                listOf(RECYCLER_VIEW_HOLDER, "I", "Ljava/util/List;")
        } ?: throw PatchException("Commentary adapter payload onBindViewHolder not found")

        payloadBind.addInstructions(
            0,
            """
                instance-of v0, p1, $POLLS_VIEW_HOLDER
                if-eqz v0, :crex_not_poll_payload
                return-void
                :crex_not_poll_payload
                nop
            """.trimIndent()
        )

        // The hidden row's PollsView never receives its PollData, but its inner RecyclerView can
        // still be prefetched. getItemCount() swallows the null with a catch and answers 2, so
        // the inner adapter then binds the question row and reads PollData.a on null (second
        // crash seen in the log). With no poll set, report no items instead. getItemCount has
        // .locals 2, so v0 is free at its first instruction.
        val itemCount = mutableClassDefBy(POLLS_ADAPTER).methods.singleOrNull { method ->
            method.name == "getItemCount" && method.returnType == "I" && method.parameterTypes.isEmpty()
        } ?: throw PatchException("PollsAdapter getItemCount not found")

        itemCount.addInstructions(
            0,
            """
                iget-object v0, p0, $POLLS_ADAPTER->a:Lin/cricketexchange/app/cricketexchange/polls/PollsView${'$'}PollData;
                if-nez v0, :crex_has_poll
                const/4 v0, 0x0
                return v0
                :crex_has_poll
                nop
            """.trimIndent()
        )
    }
}
