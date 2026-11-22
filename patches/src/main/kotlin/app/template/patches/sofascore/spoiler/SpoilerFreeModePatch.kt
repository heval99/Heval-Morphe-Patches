package app.template.patches.sofascore.spoiler

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.HevalListFeature
import app.template.patches.shared.hevalcontrols.addHevalGate
import app.template.patches.shared.hevalcontrols.featurePatch
import app.template.patches.shared.requireNoBranchTargetAt
import app.template.patches.sofascore.controls.SofascoreControlsCategory
import app.template.patches.sofascore.controls.sofascoreControls
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val SPOILER_FREE = "Lapp/template/extension/sofascore/SpoilerFree;"
internal const val SCORE_TEXT_VIEW = "Lcom/sofascore/results/view/EventListScoreTextView;"
internal const val EVENT_ACTIVITY = "Lcom/sofascore/results/event/EventActivity;"
internal const val LEAGUE_ACTIVITY = "Lcom/sofascore/results/league/LeagueActivity;"
private const val MODEL = "Lcom/sofascore/model/mvvm/model/"
private const val EVENT = "${MODEL}Event;"
private const val UNIQUE_TOURNAMENT = "${MODEL}UniqueTournament;"
private const val REMOTE_MESSAGE = "Lcom/google/firebase/messaging/RemoteMessage;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val REMOTE_VIEWS = "Landroid/widget/RemoteViews;"
private const val CONTEXT = "Landroid/content/Context;"
private const val MENU = "Landroid/view/Menu;"

/** The master switch: marking (the menu items) and every surface below depend on it. */
internal val SPOILER_FREE_MODE = HevalFeature(
    id = "sofascore.spoiler_free",
    calm = true,
    name = "Spoiler-free mode",
    description = "Adds a Spoiler-free item to match and competition menus to hide their scores.",
    category = SofascoreControlsCategory.SPOILER_FREE,
    icon = "eye_off",
)

/** Surface: pushes of marked matches. */
internal val SPOILER_NOTIFICATIONS = HevalFeature(
    id = "sofascore.spoiler_notifications",
    calm = true,
    name = "Hide alerts for marked matches",
    description = "Blocks alerts, goals included, for the matches you mark.",
    category = SofascoreControlsCategory.SPOILER_FREE,
    icon = "eye_off",
    dependencies = listOf(SPOILER_FREE_MODE.id),
)

/** Surface: the score and status cells of the match list rows. */
internal val SPOILER_LISTS = HevalFeature(
    id = "sofascore.spoiler_lists",
    calm = true,
    name = "Hide scores in match lists",
    description = "Hides the scores of marked matches in match lists.",
    category = SofascoreControlsCategory.SPOILER_FREE,
    icon = "eye_off",
    dependencies = listOf(SPOILER_FREE_MODE.id),
)

/** Surface: the home-screen widget's match rows. */
internal val SPOILER_WIDGET = HevalFeature(
    id = "sofascore.spoiler_widget",
    calm = true,
    name = "Hide scores in the home screen widget",
    description = "Hides the scores of marked matches in the widget.",
    category = SofascoreControlsCategory.SPOILER_FREE,
    icon = "eye_off",
    dependencies = listOf(SPOILER_FREE_MODE.id),
)

/** The matches marked on their match page (Sofascore event ids); the user can un-mark them here. */
internal val SPOILER_EVENTS = HevalListFeature(
    id = "sofascore.spoiler_events",
    name = "Marked matches",
    description = "Remove a match here to show its score again.",
    category = SofascoreControlsCategory.SPOILER_FREE,
    icon = "eye_off",
    dependencies = listOf(SPOILER_FREE_MODE.id),
)

/** The competitions marked on their page (UniqueTournament ids, like "Hide competitions"). */
internal val SPOILER_COMPETITIONS = HevalListFeature(
    id = "sofascore.spoiler_competitions",
    name = "Marked competitions",
    description = "Remove a competition here to show its scores again.",
    category = SofascoreControlsCategory.SPOILER_FREE,
    icon = "eye_off",
    dependencies = listOf(SPOILER_FREE_MODE.id),
)

/** Every feature this patch registers, in registration order. */
internal val SPOILER_FEATURES = listOf(
    SPOILER_FREE_MODE, SPOILER_NOTIFICATIONS, SPOILER_LISTS, SPOILER_WIDGET, SPOILER_EVENTS, SPOILER_COMPETITIONS,
)

/** The widget row's score cells and status cell; SpoilerFree.maskWidgetRow looks them up by these names. */
internal val WIDGET_SCORE_IDS = listOf(
    "first_main", "second_main", "first_secondary", "second_secondary", "first_penalties", "second_penalties",
)
internal const val WIDGET_STATUS_ID = "status_text"

/** The model getters SpoilerFree calls by name through reflection; a rename fails here. */
private val REFLECTED = mapOf(
    EVENT to listOf("getTournament", "getHomeTeam", "getAwayTeam", "getStartTimestamp"),
    "${MODEL}FootballEvent;" to listOf("getId"),
    "${MODEL}Tournament;" to listOf("getUniqueTournament"),
    UNIQUE_TOURNAMENT to listOf("getId", "getName", "getCategory"),
    "${MODEL}Category;" to listOf("getName"),
    "${MODEL}Team;" to listOf("getName"),
)

private fun BytecodePatchContext.requireReflected() {
    REFLECTED.forEach { (type, names) ->
        val present = classDefBy(type).methods.filter { it.parameterTypes.isEmpty() }.map { it.name }.toSet()
        names.filterNot(present::contains).takeIf { it.isNotEmpty() }?.let {
            throw PatchException("Spoiler-free mode: $type no longer has the getters $it")
        }
    }
}

private fun Method.params() = parameterTypes.map(CharSequence::toString)
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.methodRef() =
    (this as? ReferenceInstruction)?.reference as? MethodReference
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.fieldRef() =
    (this as? ReferenceInstruction)?.reference as? FieldReference
private val FieldReference.smali get() = "$definingClass->$name:$type"

/** The one instance method of [type] with [returnType] and [params]; throws on any other count. */
private fun BytecodePatchContext.single(type: String, returnType: String, params: List<String>, what: String): MutableMethod {
    val found = mutableClassDefBy(type).methods.filter {
        it.returnType == returnType && it.params() == params && !it.isStatic() && it.implementation != null
    }
    return found.singleOrNull() ?: throw PatchException("$type: ${found.size} $what methods, expected one")
}

/** Locals the gate and the hook need; throws when the method has fewer. */
private fun MutableMethod.requireLocals(count: Int) {
    val locals = implementation!!.registerCount - (parameterTypes.size + if (isStatic()) 0 else 1)
    if (locals < count) throw PatchException("$definingClass->$name has $locals locals, the hook needs $count")
}

/**
 * Master-switch hook at the start of [method]: `SpoilerFree.<helper>(p0, p1)`, then the stock code.
 * p0 and p1 are consecutive, so the /range call works at any register count.
 */
private fun MutableMethod.addPageHook(helper: String, descriptor: String) {
    requireLocals(1)
    requireNoBranchTargetAt(0, "Spoiler-free $helper")
    addHevalGate(SPOILER_FREE_MODE.id) {
        "invoke-static/range { p0 .. p1 }, $SPOILER_FREE->$helper$descriptor"
    }
}

/**
 * The match row binder (26.10.26: qbh.d(IILcm1;)V): the instance V method that reads at least five
 * EventListScoreTextView fields of one view binding and asks its last parameter (the row state)
 * for its Event. EventListScoreTextView keeps its name (it is referenced from layouts). Holds the
 * binder, the binding field of the binder's own class (smali), the cell fields and the Event getter.
 */
private class RowBinder(
    val method: MutableMethod,
    val binding: String,
    val cells: List<FieldReference>,
    val eventGetter: MethodReference,
)

private fun BytecodePatchContext.rowBinder(): RowBinder {
    data class Found(val type: String, val method: Method, val bindingType: String, val cells: List<FieldReference>, val getter: MethodReference)
    val found = mutableListOf<Found>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.returnType != "V" || method.isStatic() || method.parameterTypes.isEmpty()) return@forEach
            val insns = method.implementation?.instructions?.toList() ?: return@forEach
            val cells = insns.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.fieldRef() }
                .filter { it.type == SCORE_TEXT_VIEW }.distinctBy { it.name }
            val owners = cells.map { it.definingClass }.distinct()
            if (cells.size < 5 || owners.size != 1) return@forEach
            val state = method.parameterTypes.last().toString()
            val getters = insns.mapNotNull { it.methodRef() }.filter {
                it.definingClass == state && it.returnType == EVENT && it.parameterTypes.isEmpty()
            }.distinctBy { it.name }
            if (getters.size != 1) return@forEach
            found += Found(classDef.type, method, owners.single(), cells, getters.single())
        }
    }
    val row = found.singleOrNull() ?: throw PatchException("${found.size} match row binders (5+ score cells and an Event getter), expected one")
    val bindings = classDefBy(row.type).fields.filter { it.type == row.bindingType && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val binding = bindings.singleOrNull()
        ?: throw PatchException("${row.type} has ${bindings.size} ${row.bindingType} fields, expected one (the row binding)")
    val method = mutableClassDefBy(row.type).methods.single {
        it.name == row.method.name && it.returnType == "V" && it.params() == row.method.params()
    }
    return RowBinder(method, "${row.type}->${binding.name}:${binding.type}", row.cells, row.getter)
}

/**
 * The home-screen widget's match row builder (26.10.26: j6p.b(Event)RemoteViews): the instance
 * (Event)RemoteViews method that loads the ids of widget_row_event's first_main and second_main
 * score cells. Its class holds the widget's Context in its only instance Context field.
 */
private fun BytecodePatchContext.widgetRow(): Pair<MutableMethod, String> {
    val first = resourceId(ResourceType.ID, "first_main").toInt()
    val second = resourceId(ResourceType.ID, "second_main").toInt()
    (WIDGET_SCORE_IDS + WIDGET_STATUS_ID).forEach { resourceId(ResourceType.ID, it) }
    fun Method.loads(id: Int) = implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == id } == true
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        classDef.methods.forEach {
            if (it.returnType == REMOTE_VIEWS && it.params() == listOf(EVENT) && !it.isStatic() && it.loads(first) && it.loads(second)) {
                found += classDef.type to it
            }
        }
    }
    val (type, row) = found.singleOrNull() ?: throw PatchException("${found.size} widget match row builders, expected one")
    val contexts = classDefBy(type).fields.filter { it.type == CONTEXT && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val context = contexts.singleOrNull() ?: throw PatchException("$type has ${contexts.size} Context fields, expected one")
    val method = mutableClassDefBy(type).methods.single { it.name == row.name && it.returnType == REMOTE_VIEWS && it.params() == listOf(EVENT) }
    return method to "$type->${context.name}:$CONTEXT"
}

/** Every register an instruction reads or writes, wide pairs not expanded. */
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
    is TwoRegisterInstruction -> listOf(registerA, registerB)
    is OneRegisterInstruction -> listOf(registerA)
    else -> emptyList()
}

/**
 * The Compose row model hooks (26.11.09: ji7 / mi7.m): where the row model is built and what the
 * constructor takes. The row model class is the one named by the "EventRowModel(eventId=" toString.
 */
private class ComposeRow(
    val mapper: MutableMethod,
    val newInstanceIndex: Int,
    val eventRegister: Int,
    val constructor: MutableMethod,
    val rowText: String,
)

private fun BytecodePatchContext.composeRow(): ComposeRow {
    val rowType = EventRowModelToStringFingerprint.method.definingClass
    val constructors = mutableClassDefBy(rowType).methods.filter { it.name == "<init>" }
    val constructor = constructors.singleOrNull()
        ?: throw PatchException("$rowType has ${constructors.size} constructors, expected one (the Compose row model)")
    val p = constructor.params()
    // (eventId, timeUpper, timeLower, home, away, additionalInfo, ...): the first six parameters.
    if (p.size < 6 || p[0] != "I" || p[1] != p[2] || p[1] != p[5] || p[3] != p[4] || p[3] == p[1] || constructor.implementation == null) {
        throw PatchException("$rowType.<init> parameters are $p, expected (I, RowText, RowText, Line, Line, RowText, ...)")
    }
    val rowText = p[1]
    val lines = classDefBy(p[3]).fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == rowText }
    if (lines.size != 4) {
        throw PatchException("${p[3]} has ${lines.size} $rowText fields, expected four (name and three scores)")
    }
    // The line's constructor takes the name first and the game score, set score and score last; the
    // extension sorts the RowText fields by name itself and nulls the last three.
    val lineInit = classDefBy(p[3]).methods.singleOrNull { it.name == "<init>" }
    val lineCtor = lineInit?.parameterTypes?.map(CharSequence::toString)
    if (lineInit != null && lineCtor != null && lineInit.implementation != null) {
        // The fields the constructor assigns the RowText parameters to (name, game, set, score, in
        // parameter order) must sort ascending by name, the order the extension sorts the fields into.
        val impl = lineInit.implementation!!
        val firstParam = impl.registerCount - lineCtor.sumOf { (if (it == "J" || it == "D") 2 else 1) }
        val rowTextRegisters = lineCtor.indices.filter { lineCtor[it] == rowText }.map { i ->
            firstParam + lineCtor.take(i).sumOf { (if (it == "J" || it == "D") 2 else 1) }
        }
        val assigned = rowTextRegisters.map { register ->
            val stores = impl.instructions.filter {
                it.opcode == Opcode.IPUT_OBJECT && (it as OneRegisterInstruction).registerA == register
            }.mapNotNull { it.fieldRef() }.filter { it.definingClass == p[3] && it.type == rowText }
            stores.singleOrNull()?.name
        }
        if (assigned.any { it == null } || assigned.size != 4 || assigned.zipWithNext().any { (a, b) -> a!! >= b!! }) {
            throw PatchException("${p[3]}: the RowText fields assigned by the constructor are $assigned, expected four names in ascending order (name, game, set, score)")
        }
    }
    if (lineCtor == null || lineCtor.size < 4 || lineCtor.first() != rowText || lineCtor.takeLast(3).any { it != rowText } || lineCtor.count { it == rowText } != 4) {
        throw PatchException("${p[3]}.<init> parameters are $lineCtor, expected (RowText name, ..., RowText game, RowText set, RowText score)")
    }
    if (constructor.implementation!!.registerCount < 6) throw PatchException("$rowType.<init> has fewer registers than p1..p5")

    val mapper = EventRowModelMapperFingerprint.method
    val insns = mapper.implementation!!.instructions.toList()
    val creates = insns.indices.filter {
        insns[it].opcode == Opcode.NEW_INSTANCE && ((insns[it] as ReferenceInstruction).reference as? TypeReference)?.type == rowType
    }
    val index = creates.singleOrNull()
        ?: throw PatchException("${mapper.definingClass}->${mapper.name} creates $rowType ${creates.size} times, expected once")
    val register = (insns[index] as OneRegisterInstruction).registerA
    if (register > 15) throw PatchException("${mapper.definingClass}->${mapper.name}: the row model is built in v$register, above v15")
    // The instruction before it that touches that register must hand it to an Event call: the new-instance
    // reuses the register that held the Event until now.
    val last = (index - 1 downTo 0).firstOrNull { register in insns[it].registers() }
        ?: throw PatchException("${mapper.definingClass}->${mapper.name}: v$register is never used before the row model is built")
    val ref = (insns[last] as? ReferenceInstruction)?.reference as? MethodReference
    val firstIsEvent = ref != null && when (insns[last].opcode) {
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> ref.parameterTypes.firstOrNull()?.toString() == EVENT
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> ref.definingClass == EVENT
        else -> false
    } && insns[last].registers().first() == register
    if (!firstIsEvent) {
        throw PatchException("${mapper.definingClass}->${mapper.name}: v$register does not hold the Event before the row model is built (last use: ${insns[last].opcode} $ref)")
    }
    return ComposeRow(mapper, index, register, constructor, rowText)
}

// Spoiler-free mode (2026-11-08, owner request), phase 1. The user marks a match on its match page or
// a competition on its page ("Spoiler-free: on/off" in the overflow menu); the marks are two Patch
// settings lists that depend on the master switch. Each surface has its own switch (on by default,
// depending on the master), so the user picks what is hidden; turning a switch off restores stock
// for that surface only. Stored on the phone only (SpoilerFree in extensions/sofascore). Read at use
// time (menu, each push, each bind), so no restart is needed.
//
// 1. Notifications (sofascore.spoiler_notifications): FCMListenerService.onMessageReceived returns
//    at entry when the "notification" JSON opens a marked event ("open": target details, highlights
//    or lineups, "id" the event id). The message bundle is RemoteMessage's only Bundle field. Pushes
//    carry no competition id, so a marked competition does not block its pushes (only marked
//    matches do). Pushes with a notification payload shown by the system itself while the app is in
//    the background never reach this method.
// 2. Match page (master): EventActivity.onCreateOptionsMenu adds the item (hidden until the match is
//    known); EventActivity's only (Event)V method, which onPrepareOptionsMenu calls with the match,
//    hands the Event over. The item handles its own click (EventActivity has no
//    onOptionsItemSelected).
// 3. Competition page (master): the same with LeagueActivity and its only (UniqueTournament)V method.
// 4. Match list rows (sofascore.spoiler_lists): at the start of the row binder every score cell
//    (EventListScoreTextView: scores, period scores, aggregate, penalties and the status cell) is
//    marked for the row's Event, and EventListScoreTextView.setText answers "" for a marked cell, so
//    later updates (the ticking minute, live scores) stay hidden too. The kickoff time has its own
//    cell (a TextView) and stays. Only the display changes: the Event, sorting, the Live tab, live
//    updates and favourites in the database are stock. On 26.11.09 these rows exist only in the
//    Player and Manager match lists.
// 4b. Compose match rows (the main Matches tab, team, league, live, favourites and match page lists,
//    same switch; apart from the main Matches tab these screens are expected from static analysis of
//    who renders the row model, not observed on a device):the row model (EventRowModel, found by its toString constant) is built in two
//    places, the mapper from an Event and a copy that only swaps the status text. The mapper tells
//    SpoilerFree.noteEvent the Event just before it builds the model (the model carries only the
//    event id, so the competition mark is noted there), and the model's only constructor starts with
//    SpoilerFree.maskRow(eventId, kickoff, status, home line, away line): for a marked match it nulls
//    the three score fields of both team lines (found by type and order) and the status text (live
//    minute, "FT"), so the row shows no score. The kickoff time, team names and the extra info
//    (postponed and the like) stay. The switch is read in Java; the constructor has no free register.
// 5. Home-screen widget (sofascore.spoiler_widget): before the widget row builder returns, a marked
//    match's score cells are emptied and its status cell shows the kickoff time.
// Not covered in phase 1: the match page header and tabs (incidents, statistics, lineups, momentum),
// search, featured match views, the chat/share card, team form, standings, the winner colour of the
// team names (list rows and widget) and the extra info text of a Compose row, Wear and lock-screen
// notifications. A Compose row kept in memory (the live status copy) shows scores again only after
// its list refreshes when the user un-marks a match.
@Suppress("unused")
val spoilerFreeModePatch = sofascoreControls.featurePatch(
    SPOILER_FREE_MODE,
    // The Manager patch keeps its name and description; only the Patch settings row was reworded (2026-11-11).
    description = "Lets you hide scores for matches and competitions you mark, and goal alerts for matches you mark.",
    default = true,
) {
    // The Sofascore extension comes with the Patch settings host (its Settings row lives there).
    classDefBy(SPOILER_FREE)
    requireReflected()

    // Find every site before the first edit.
    val push = FcmMessageReceivedFingerprint.method
    val bundles = classDefBy(REMOTE_MESSAGE).fields.filter { it.type == BUNDLE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val bundle = bundles.singleOrNull()
        ?: throw PatchException("$REMOTE_MESSAGE has ${bundles.size} Bundle fields, expected one (the message)")
    push.requireLocals(1)

    // onCreateOptionsMenu is the framework override, so its name is stable.
    fun BytecodePatchContext.menuBuilder(type: String) = mutableClassDefBy(type).methods.filter {
        it.name == "onCreateOptionsMenu" && it.params() == listOf(MENU) && it.returnType == "Z" && it.implementation != null
    }.singleOrNull() ?: throw PatchException("$type has no onCreateOptionsMenu(Menu)Z of its own")
    val matchMenu = menuBuilder(EVENT_ACTIVITY)
    val matchSetter = single(EVENT_ACTIVITY, "V", listOf(EVENT), "(Event)V")
    val competitionMenu = menuBuilder(LEAGUE_ACTIVITY)
    val competitionSetter = single(LEAGUE_ACTIVITY, "V", listOf(UNIQUE_TOURNAMENT), "(UniqueTournament)V")

    val setTexts = mutableClassDefBy(SCORE_TEXT_VIEW).methods.filter {
        it.returnType == "V" && it.params() == listOf("Ljava/lang/String;") && !it.isStatic() && it.implementation != null
    }
    val setText = setTexts.singleOrNull()
        ?: throw PatchException("$SCORE_TEXT_VIEW has ${setTexts.size} (String)V methods, expected one (setText)")
    if (setText.name != "setText") throw PatchException("$SCORE_TEXT_VIEW: the (String)V method is ${setText.name}, SpoilerFree calls setText")
    setText.requireLocals(1)

    val row = rowBinder()
    row.method.requireLocals(3)
    row.method.requireNoBranchTargetAt(0, "Spoiler-free row hook")

    val compose = composeRow()

    val (widget, widgetContext) = widgetRow()
    val widgetInsns = widget.instructions.toList()
    val returns = widgetInsns.indices.filter { widgetInsns[it].opcode == Opcode.RETURN_OBJECT }
    val widgetReturn = returns.singleOrNull()
        ?: throw PatchException("${widget.definingClass}->${widget.name} has ${returns.size} returns, expected one")
    val rowViews = (widgetInsns[widgetReturn] as OneRegisterInstruction).registerA
    if (rowViews > 15) throw PatchException("${widget.definingClass}->${widget.name} returns v$rowViews, above v15")
    val widgetEvent = widget.implementation!!.registerCount - 1 // p1
    if (widgetInsns.any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == widgetEvent }) {
        throw PatchException("${widget.definingClass}->${widget.name} overwrites p1 (the Event)")
    }
    val (scratch, contextReg, eventReg) = (0..3).filter { it != rowViews }.take(3)
    widget.requireLocals(4)

    // 1. Notifications.
    push.addHevalGate(SPOILER_NOTIFICATIONS.id) { v ->
        """
            move-object/from16 $v, p1
            iget-object $v, $v, $REMOTE_MESSAGE->${bundle.name}:$BUNDLE
            invoke-static { $v }, $SPOILER_FREE->blocksPush($BUNDLE)Z
            move-result $v
            if-eqz $v, :heval_stock
            return-void
        """
    }

    // 2. and 3. The two menu items and the page subjects.
    matchMenu.addPageHook("addMatchItem", "(Ljava/lang/Object;$MENU)V")
    matchSetter.addPageHook("onMatch", "(Ljava/lang/Object;Ljava/lang/Object;)V")
    competitionMenu.addPageHook("addCompetitionItem", "(Ljava/lang/Object;$MENU)V")
    competitionSetter.addPageHook("onCompetition", "(Ljava/lang/Object;Ljava/lang/Object;)V")

    // 4. Match list rows: the cells' text filter, then the per-bind marks.
    setText.addHevalGate(SPOILER_LISTS.id) {
        """
            invoke-static/range { p0 .. p1 }, $SPOILER_FREE->scoreText(Landroid/view/View;Ljava/lang/String;)Ljava/lang/String;
            move-result-object p1
        """
    }
    val state = "p${row.method.parameterTypes.size}"
    row.method.addHevalGate(SPOILER_LISTS.id) {
        buildString {
            appendLine("move-object/from16 v1, p0")
            appendLine("iget-object v1, v1, ${row.binding}")
            appendLine("move-object/from16 v2, $state")
            appendLine("invoke-virtual { v2 }, ${row.eventGetter.definingClass}->${row.eventGetter.name}()$EVENT")
            appendLine("move-result-object v2")
            row.cells.forEach { cell ->
                appendLine("iget-object v0, v1, ${cell.smali}")
                appendLine("invoke-static { v0, v2 }, $SPOILER_FREE->markRow(Landroid/view/View;Ljava/lang/Object;)V")
            }
        }
    }

    // 4b. Compose match rows: the mapper notes the Event (the new-instance is a branch target, so the
    // note takes over its labels), the row model constructor masks the scores before they are stored.
    compose.mapper.addInstructionsAtControlFlowLabelChecked(
        compose.newInstanceIndex,
        "invoke-static { v${compose.eventRegister} }, $SPOILER_FREE->noteEvent(Ljava/lang/Object;)V",
    )
    compose.constructor.addInstructionsChecked(
        0,
        """
            invoke-static/range { p1 .. p5 }, $SPOILER_FREE->maskRow(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object p3
            check-cast p3, ${compose.rowText}
        """,
    )

    // 5. The widget row. The return is a branch target: a nop takes over its labels first, so every
    // path into the return runs the gate.
    widget.addInstructionsAtControlFlowLabelChecked(widgetReturn, "nop")
    widget.addHevalGate(SPOILER_WIDGET.id, index = widgetReturn + 1, register = scratch) {
        """
            move-object/from16 v$contextReg, p0
            iget-object v$contextReg, v$contextReg, $widgetContext
            move-object/from16 v$eventReg, p1
            invoke-static { v$contextReg, v$rowViews, v$eventReg }, $SPOILER_FREE->maskWidgetRow($CONTEXT$REMOTE_VIEWS${"Ljava/lang/Object;"})V
        """
    }

    sofascoreControls.register(SPOILER_NOTIFICATIONS)
    sofascoreControls.register(SPOILER_LISTS)
    sofascoreControls.register(SPOILER_WIDGET)
    sofascoreControls.register(SPOILER_EVENTS)
    sofascoreControls.register(SPOILER_COMPETITIONS)
}
