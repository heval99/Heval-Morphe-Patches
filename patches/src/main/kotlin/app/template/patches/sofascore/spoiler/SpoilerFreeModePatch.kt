package app.template.patches.sofascore.spoiler

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.addInstructionsWithLabelsChecked
import app.template.patches.sofascore.controls.eventTabEnum
import app.template.patches.sofascore.controls.navigateToTabSite
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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
    description = "Blocks alerts for marked matches; full time passes unless final scores stay hidden.",
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

/**
 * Surface (added 2026-11-22): the match page of a marked match (the header and the Details score
 * tables; since 2026-11-23 also the score tabs, the incidents and the share card's incidents).
 */
internal val SPOILER_MATCH_PAGE = HevalFeature(
    id = "sofascore.spoiler_match_page",
    calm = true,
    name = "Hide scores on match pages",
    description = "Hides the score, tabs and goals of a marked match's page until full time.",
    category = SofascoreControlsCategory.SPOILER_FREE,
    icon = "eye_off",
    dependencies = listOf(SPOILER_FREE_MODE.id),
)

/**
 * Option (owner 2026-11-22, added 2026-11-22): marked matches stay hidden after full time too, until un-marked. Off by
 * default (a marked match shows its score again at full time). Read only in SpoilerFree (the shared
 * decision and the push rule), so it has no gate of its own in the app code.
 */
internal val SPOILER_KEEP_FINAL_HIDDEN = HevalFeature(
    id = "sofascore.spoiler_keep_final",
    name = "Keep final scores hidden",
    description = "Keeps the scores of marked matches hidden after full time too.",
    category = SofascoreControlsCategory.SPOILER_FREE,
    default = false,
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
    SPOILER_FREE_MODE, SPOILER_NOTIFICATIONS, SPOILER_LISTS, SPOILER_WIDGET, SPOILER_MATCH_PAGE, SPOILER_KEEP_FINAL_HIDDEN,
    SPOILER_EVENTS, SPOILER_COMPETITIONS,
)

/** Header parts that show the score besides the result view; SpoilerFree.onHeader looks them up by these names. */
internal val HEADER_SCORE_IDS = listOf(
    "additional_info_container", "container_result_collapsed", "collapsed_result_first_team",
    "collapsed_result_second_team", "collapsed_result_view",
)

/** The widget row's score cells and status cell; SpoilerFree.maskWidgetRow looks them up by these names. */
internal val WIDGET_SCORE_IDS = listOf(
    "first_main", "second_main", "first_secondary", "second_secondary", "first_penalties", "second_penalties",
)
internal const val WIDGET_STATUS_ID = "status_text"

/** The model getters SpoilerFree calls by name through reflection; a rename fails here. */
private val REFLECTED = mapOf(
    // getStatusType: the status type string ("inprogress", "finished") the shared decision reads.
    EVENT to listOf("getTournament", "getHomeTeam", "getAwayTeam", "getStartTimestamp", "getStatusType"),
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

/**
 * The match page header (26.11.09: base gr1; setEvent in uq1 (football and the generic sports,
 * also used by c24, w5a, ww1), fvm (tennis), gzo (volleyball) and ss3 (chat)): the base's
 * setResultView, every concrete setEvent(Event)V below the base, and the one setFootballGoals(List)V.
 */
private class MatchHeader(
    val resultViewSetter: MutableMethod,
    val setEvents: List<MutableMethod>,
    val footballGoals: MutableMethod,
)

private fun BytecodePatchContext.matchHeader(): MatchHeader {
    val setter = MatchHeaderResultViewSetterFingerprint.method
    val base = setter.definingClass
    val supers = mutableMapOf<String, String?>()
    classDefForEach { supers[it.type] = it.superclass }
    fun below(type: String): Boolean {
        var current = supers[type]
        while (current != null) {
            if (current == base) return true
            current = supers[current]
        }
        return false
    }
    val headers = supers.keys.filter(::below)
    val setEvents = headers.flatMap { type ->
        mutableClassDefBy(type).methods.filter {
            it.name == "setEvent" && it.returnType == "V" && it.params() == listOf(EVENT) && !it.isStatic() && it.implementation != null
        }
    }
    if (setEvents.isEmpty()) throw PatchException("no header below $base implements setEvent(Event)V")
    val goals = headers.flatMap { type ->
        mutableClassDefBy(type).methods.filter {
            it.name == "setFootballGoals" && it.returnType == "V" && it.params() == listOf("Ljava/util/List;") && it.implementation != null
        }
    }
    val footballGoals = goals.singleOrNull()
        ?: throw PatchException("${goals.size} headers below $base have setFootballGoals(List)V, expected one")
    return MatchHeader(mutableClassDefBy(base).methods.single { it.name == setter.name && it.params() == setter.params() }, setEvents, footballGoals)
}

/** The Details tab's period score tables, by the layout their getLayoutId() returns (26.11.09: vhm, mk5, u75). */
internal val DETAILS_TABLE_LAYOUTS = listOf("details_table", "darts_table_score_view", "details_table_cricket")

/**
 * The Details tab score modules (26.11.09): the module base hc (a FrameLayout whose kept
 * getShowAsCard / setNewLayoutParams / getLayoutId mark it; its setVisibility(I)V override is
 * hooked), the three period score tables below it, each found by the layout its getLayoutId()
 * returns, with the (Event)V method of its one interface (fc2.a), and the period points graph.
 */
private class DetailsModules(val setVisibility: MutableMethod, val tables: List<MutableMethod>, val graph: MutableMethod, val share: MutableMethod)

private fun BytecodePatchContext.detailsModules(): DetailsModules {
    val layouts = DETAILS_TABLE_LAYOUTS.associateBy { resourceId(ResourceType.LAYOUT, it).toInt() }
    val found = mutableListOf<Pair<String, String>>() // class, layout
    classDefForEach { classDef ->
        val layoutGetter = classDef.methods.singleOrNull { it.name == "getLayoutId" && it.returnType == "I" && it.parameterTypes.isEmpty() }
            ?: return@classDefForEach
        val literals = layoutGetter.implementation?.instructions?.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.orEmpty()
        literals.mapNotNull(layouts::get).singleOrNull()?.let { found += classDef.type to it }
    }
    if (found.map { it.second }.sorted() != DETAILS_TABLE_LAYOUTS.sorted()) {
        throw PatchException("Details score tables by layout: $found, expected one class each for $DETAILS_TABLE_LAYOUTS")
    }
    val bases = found.map { classDefBy(it.first).superclass!! }.distinct()
    val base = bases.singleOrNull() ?: throw PatchException("the Details score tables have the base classes $bases, expected one")
    val baseDef = classDefBy(base)
    if (baseDef.superclass != "Landroid/widget/FrameLayout;" || listOf("getShowAsCard", "setNewLayoutParams").any { n -> baseDef.methods.none { it.name == n } }) {
        throw PatchException("$base is not the Details module base (FrameLayout with getShowAsCard and setNewLayoutParams)")
    }
    val setVisibility = mutableClassDefBy(base).methods.singleOrNull {
        it.name == "setVisibility" && it.params() == listOf("I") && it.returnType == "V" && it.implementation != null
    } ?: throw PatchException("$base has no setVisibility(I)V override")
    val tables = found.map { (type, layout) ->
        val interfaces = classDefBy(type).interfaces
        val binders = interfaces.flatMap { iface ->
            classDefBy(iface).methods.filter { it.returnType == "V" && it.params() == listOf(EVENT) && AccessFlags.ABSTRACT.isSet(it.accessFlags) }
        }
        val binder = binders.singleOrNull()
            ?: throw PatchException("$type ($layout) implements ${binders.size} (Event)V interface methods, expected one")
        mutableClassDefBy(type).methods.singleOrNull { it.name == binder.name && it.params() == listOf(EVENT) && it.returnType == "V" && it.implementation != null }
            ?: throw PatchException("$type ($layout) does not implement ${binder.definingClass}->${binder.name}(Event)V")
    }
    val graph = PeriodPointsGraphFingerprint.method
    if (graph.isStatic()) throw PatchException("${graph.definingClass}->${graph.name} is static, expected the graph view's method")
    // The share modal's match header (26.11.09: ejk.f(wgd, Event)V) is a module of the same base.
    val shareLayout = resourceId(ResourceType.LAYOUT, SHARE_HEADER_LAYOUT).toInt()
    val shares = mutableListOf<String>()
    classDefForEach { classDef ->
        if (classDef.superclass != base) return@classDefForEach
        val getter = classDef.methods.singleOrNull { it.name == "getLayoutId" && it.returnType == "I" && it.parameterTypes.isEmpty() }
        if (getter?.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == shareLayout } == true) shares += classDef.type
    }
    val shareType = shares.singleOrNull() ?: throw PatchException("${shares.size} $SHARE_HEADER_LAYOUT modules, expected one")
    val shareBinders = mutableClassDefBy(shareType).methods.filter {
        it.returnType == "V" && it.params().size == 2 && it.params()[1] == EVENT && !it.isStatic() && it.implementation != null
    }
    val share = shareBinders.singleOrNull() ?: throw PatchException("$shareType has ${shareBinders.size} (?, Event)V methods, expected one")
    return DetailsModules(setVisibility, tables, graph, share)
}

/** The share modal's match header layout (score, status, teams). */
internal const val SHARE_HEADER_LAYOUT = "share_match_details_modal_header"

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

/**
 * The match page tabs a hidden page drops (phase 3, by enum constant name): every tab that shows the
 * score or how the match goes. Details (incidents emptied, score tables gone), the prematch tabs,
 * odds, standings, the knockout tree, matches and contribute stay. SpoilerFree.SPOILER_TABS holds the
 * same names; the smoke test checks both.
 */
internal val SPOILER_TAB_NAMES = listOf(
    "COMMENTARY", "AI_INSIGHTS", "OVERS", "SCORECARD", "GAMES", "LINEUPS", "BOX_SCORE", "STATISTICS",
    "MMA_STATISTICS", "HOCKEY_PLAY_BY_PLAY", "GRAPHS", "MEDIA",
)

private const val EVENT_HEAD_DATA = "${MODEL}EventHeadData;"
private const val INCIDENT = "${MODEL}Incident;"
private const val LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
internal const val SHARE_MATCH_MODAL = "Lcom/sofascore/results/sharemodal/match/ShareMatchDetailsModal;"

/** Whether the instruction writes register [register] (its result register, both halves of a wide one). */
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val a = (this as? OneRegisterInstruction)?.registerA ?: return false
    return a == register || (opcode.setsWideRegister() && a + 1 == register)
}

/**
 * The tab builder hooks (26.11.09: g55.a): the activity's register (its check-cast to EventActivity),
 * where component1() hands over the Event and in which register, and the pager adapter's (List)V
 * call with the tab list.
 */
private class TabBuilder(
    val method: MutableMethod,
    val activity: Int,
    val afterEvent: Int,
    val event: Int,
    val setTabs: Int,
    val tabs: Int,
)

private fun BytecodePatchContext.tabBuilder(): TabBuilder {
    val method = MatchTabBuilderFingerprint.method
    val label = "${method.definingClass}->${method.name}"
    val insns = method.instructions.toList()
    val casts = insns.indices.filter {
        insns[it].opcode == Opcode.CHECK_CAST && ((insns[it] as ReferenceInstruction).reference as TypeReference).type == EVENT_ACTIVITY
    }
    val cast = casts.singleOrNull() ?: throw PatchException("$label casts to EventActivity ${casts.size} times, expected once")
    val activity = (insns[cast] as OneRegisterInstruction).registerA
    val component1s = insns.indices.filter { insns[it].methodRef()?.let { r -> r.definingClass == EVENT_HEAD_DATA && r.name == "component1" } == true }
    val component1 = component1s.singleOrNull() ?: throw PatchException("$label calls EventHeadData.component1() ${component1s.size} times, expected once")
    if (cast > component1) throw PatchException("$label casts to EventActivity after component1(), expected before")
    val moved = insns.getOrNull(component1 + 1)
    if (moved?.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("$label: component1() is not followed by move-result-object")
    val event = (moved as OneRegisterInstruction).registerA
    // The adapter's (List)V call after the Event is known; its list is the ArrayList the filter fills.
    val setters = insns.indices.filter { i ->
        i > component1 && insns[i].opcode == Opcode.INVOKE_VIRTUAL &&
            insns[i].methodRef()?.let { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(LIST) } == true
    }
    val setTabs = setters.singleOrNull() ?: throw PatchException("$label has ${setters.size} (List)V calls after component1(), expected one (the tab list)")
    val tabs = (insns[setTabs] as FiveRegisterInstruction).registerD
    val built = (setTabs - 1 downTo 0).firstOrNull { insns[it].writes(tabs) }
    if (built == null || insns[built].opcode != Opcode.NEW_INSTANCE ||
        ((insns[built] as ReferenceInstruction).reference as TypeReference).type != ARRAY_LIST
    ) {
        throw PatchException("$label: the tab list v$tabs is not a new ArrayList (last write ${built?.let { insns[it].opcode }})")
    }
    // The activity register must still hold the activity at the tab list call.
    if ((cast + 1 until setTabs).any { insns[it].writes(activity) }) throw PatchException("$label overwrites the activity v$activity before the tab list call")
    if (listOf(activity, event, tabs).any { it > 15 }) throw PatchException("$label: the activity, Event or tab list is above v15")
    return TabBuilder(method, activity, component1 + 2, event, setTabs, tabs)
}

/**
 * The incidents adapter's fill method (26.11.09: k8c.F(List, y87, sti, Z, Long)V): the one instance
 * (List, ?, X, Z, Long)V method that builds incident rows with a static (Context, Incident, X) row
 * builder (v8c.a). Holds the adapter's Context field the method reads from p0.
 */
private fun BytecodePatchContext.incidentsFill(): Pair<MutableMethod, FieldReference> {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        classDef.methods.forEach { m ->
            val p = m.params()
            if (m.returnType != "V" || m.isStatic() || p.size != 5 || p[0] != LIST || p[3] != "Z" || p[4] != "Ljava/lang/Long;") return@forEach
            val insns = m.implementation?.instructions ?: return@forEach
            val builds = insns.any { insn ->
                insn.opcode == Opcode.INVOKE_STATIC && insn.methodRef()?.parameterTypes?.map(CharSequence::toString) == listOf(CONTEXT, INCIDENT, p[2])
            }
            if (builds) found += classDef.type to m
        }
    }
    val (type, fill) = found.singleOrNull() ?: throw PatchException("${found.size} incident adapter fill methods (List, ?, X, Z, Long)V, expected one")
    val label = "$type->${fill.name}"
    val contexts = fill.implementation!!.instructions.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.fieldRef() }
        .filter { it.type == CONTEXT }.distinctBy { it.smali }
    val context = contexts.singleOrNull() ?: throw PatchException("$label reads ${contexts.size} Context fields, expected one (the adapter's)")
    val method = mutableClassDefBy(type).methods.single { it.name == fill.name && it.params() == fill.params() && it.returnType == "V" }
    val registers = method.implementation!!.registerCount
    if (registers - 5 > 15) throw PatchException("$label: p1 is v${registers - 5}, above v15")
    method.requireLocals(1)
    return method to context
}

/**
 * The period incidents grouping of the adapters below it (26.11.09: uv7.K and its override uzo.K,
 * (ArrayList, Event)LinkedHashMap): every concrete implementation that reads PeriodIncident and the
 * running score (Incident.getHomeScore${'$'}default). Throws unless they are one method and its overrides.
 */
private fun BytecodePatchContext.periodGroupings(): List<MutableMethod> {
    val found = mutableListOf<Pair<String, Method>>()
    val supers = mutableMapOf<String, String?>()
    classDefForEach { classDef ->
        supers[classDef.type] = classDef.superclass
        classDef.methods.forEach { m ->
            if (m.returnType != "Ljava/util/LinkedHashMap;" || m.params() != listOf(ARRAY_LIST, EVENT) || m.isStatic()) return@forEach
            val refs = m.implementation?.instructions?.mapNotNull { it.methodRef() } ?: return@forEach
            if (refs.any { it.definingClass == "${MODEL}Incident\$PeriodIncident;" } && refs.any { it.definingClass == INCIDENT && it.name == "getHomeScore\$default" }) {
                found += classDef.type to m
            }
        }
    }
    if (found.isEmpty()) throw PatchException("no (ArrayList, Event)LinkedHashMap period incident grouping found")
    val names = found.map { it.second.name }.distinct()
    val name = names.singleOrNull() ?: throw PatchException("period incident groupings have the names $names, expected one method and its overrides")
    fun below(type: String, base: String): Boolean {
        var current = supers[type]
        while (current != null) {
            if (current == base) return true
            current = supers[current]
        }
        return false
    }
    val types = found.map { it.first }
    val bases = types.filter { t -> types.none { other -> other != t && below(t, other) } }
    val base = bases.singleOrNull() ?: throw PatchException("period incident groupings in $types do not share one base")
    // Every concrete override below the base must be among them, so none is left unhooked.
    val overrides = supers.keys.filter { it == base || below(it, base) }.filter { t ->
        classDefBy(t).methods.any { it.name == name && it.params() == listOf(ARRAY_LIST, EVENT) && it.implementation != null }
    }
    if (overrides.toSet() != types.toSet()) throw PatchException("period incident groupings $types, but $name is implemented in $overrides")
    return types.map { t -> mutableClassDefBy(t).methods.single { it.name == name && it.params() == listOf(ARRAY_LIST, EVENT) } }
}

/**
 * The share modal's incident reads (26.11.09: ShareMatchDetailsModal.j0 calls m0() seven times):
 * every `invoke-virtual {vM}, <the modal's only ()ArrayList method>` plus its move-result-object, in
 * the modal's methods. Each holds the method, the index after the move-result, the modal register and
 * the result register. The modal's only ()Event method is what SpoilerFree reads the match from.
 */
private class ShareRead(val method: MutableMethod, val after: Int, val modal: Int, val list: Int)

private fun BytecodePatchContext.shareIncidentReads(): List<ShareRead> {
    val modal = classDefBy(SHARE_MATCH_MODAL)
    val getters = modal.methods.filter { it.returnType == ARRAY_LIST && it.parameterTypes.isEmpty() && !it.isStatic() }
    val getter = getters.singleOrNull() ?: throw PatchException("$SHARE_MATCH_MODAL has ${getters.size} ()ArrayList methods, expected one (the incidents)")
    val events = modal.methods.filter { it.returnType == EVENT && it.parameterTypes.isEmpty() && !it.isStatic() }
    if (events.size != 1) throw PatchException("$SHARE_MATCH_MODAL has ${events.size} ()Event methods, expected one (SpoilerFree reads the match from it)")
    val reads = mutableClassDefBy(SHARE_MATCH_MODAL).methods.filter { it.implementation != null }.flatMap { method ->
        val insns = method.instructions.toList()
        insns.indices.filter { i ->
            insns[i].methodRef()?.let {
                it.definingClass == SHARE_MATCH_MODAL && it.name == getter.name && it.returnType == ARRAY_LIST && it.parameterTypes.isEmpty()
            } == true
        }.map { i ->
            val label = "${method.definingClass}->${method.name}@$i"
            // Only the plain invoke-virtual form is hooked; any other form (range, super) fails here.
            if (insns[i].opcode != Opcode.INVOKE_VIRTUAL) throw PatchException("$label reads the incidents with ${insns[i].opcode}, expected invoke-virtual")
            val moved = insns.getOrNull(i + 1)
            if (moved?.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("$label: the incidents read is not followed by move-result-object")
            val receiver = (insns[i] as FiveRegisterInstruction).registerC
            val list = (moved as OneRegisterInstruction).registerA
            if (receiver == list || receiver > 15 || list > 15) throw PatchException("$label: modal v$receiver / list v$list cannot be passed on")
            method.requireNoBranchTargetAt(i + 2, "Spoiler-free share incidents hook")
            ShareRead(method, i + 2, receiver, list)
        }
    }
    if (reads.isEmpty()) throw PatchException("$SHARE_MATCH_MODAL never reads its incidents (${getter.name})")
    return reads
}

/**
 * The search result row binder (26.11.09: zb.g(xi2, I, Event)V): the one (binding, I, Event)V
 * method that loads R.string.match_result_template. Holds the binding field of the score TextView
 * (the receiver of the setText(CharSequence) after the template is loaded).
 */
private fun BytecodePatchContext.searchRow(): Pair<MutableMethod, FieldReference> {
    val template = resourceId(ResourceType.STRING, "match_result_template").toInt()
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        classDef.methods.forEach { m ->
            val p = m.params()
            if (m.returnType != "V" || m.isStatic() || p.size != 3 || p[1] != "I" || p[2] != EVENT) return@forEach
            if (m.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == template } == true) found += classDef.type to m
        }
    }
    val (type, row) = found.singleOrNull() ?: throw PatchException("${found.size} (binding, I, Event)V methods load match_result_template, expected one (the search row)")
    val label = "$type->${row.name}"
    val insns = row.implementation!!.instructions.toList()
    val load = insns.indexOfFirst { (it as? NarrowLiteralInstruction)?.narrowLiteral == template }
    val setText = (load + 1 until insns.size).firstOrNull { i ->
        insns[i].methodRef()?.let { it.definingClass == "Landroid/widget/TextView;" && it.name == "setText" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;") } == true
    } ?: throw PatchException("$label sets no text after loading match_result_template")
    val view = (insns[setText] as FiveRegisterInstruction).registerC
    val read = (setText - 1 downTo 0).filter { insns[it].writes(view) }.firstOrNull { insns[it].opcode == Opcode.IGET_OBJECT }
        ?: throw PatchException("$label: the score view v$view is not read from a field")
    val field = insns[read].fieldRef()!!
    if (field.definingClass != row.params()[0]) throw PatchException("$label: the score view comes from $field, not from the binding ${row.params()[0]}")
    val registers = row.implementation!!.registerCount
    val p1 = registers - 3
    val p3 = registers - 1
    if (insns.any { it.writes(p1) || it.writes(p3) }) throw PatchException("$label overwrites p1 (the binding) or p3 (the Event)")
    val returns = insns.indices.filter { insns[it].opcode == Opcode.RETURN_VOID }
    if (returns.size != 1) throw PatchException("$label has ${returns.size} returns, expected one")
    val method = mutableClassDefBy(type).methods.single { it.name == row.name && it.params() == row.params() && it.returnType == "V" }
    method.requireLocals(2)
    return method to field
}

// Spoiler-free mode (2026-11-08, owner request), phase 1. The user marks a match on its match page or
// a competition on its page ("Spoiler-free: on/off" in the overflow menu); the marks are two Patch
// settings lists that depend on the master switch. Each surface has its own switch (on by default,
// depending on the master), so the user picks what is hidden; turning a switch off restores stock
// for that surface only. Stored on the phone only (SpoilerFree in extensions/sofascore). Read at use
// time (menu, each push, each bind), so no restart is needed.
//
// Full time (owner 2026-11-22, added 2026-11-22): a marked match is hidden only while it is not over. Every score
// surface (4, 4b, 5) asks one shared decision, SpoilerFree.hidesScore: marked (match or competition)
// and Event.getStatusType() is not "finished" (an unreadable or unknown status hides). The option
// sofascore.spoiler_keep_final (off by default, depends on the master) keeps finished marked matches
// hidden too. The status is read when a row is built or bound, so a finished match shows its score
// at the next redraw with no refresh or un-marking (inferred statically: a status or score change
// arrives as a new list item and is mapped/bound again).
//
// 1. Notifications (sofascore.spoiler_notifications): FCMListenerService.onMessageReceived returns
//    at entry when the "notification" JSON opens a marked event ("open": target details, highlights
//    or lineups, "id" the event id), except the full-time result ("icon" "finish") while
//    sofascore.spoiler_keep_final is off (2026-11-22; a push without a readable icon is dropped).
//    The message bundle is RemoteMessage's only Bundle field. Pushes carry no competition id, so a
//    marked competition does not block its pushes (only marked matches do). Not covered: pushes
//    with a notification payload shown by the system itself while the app is in the background
//    (they never reach this method), and alerts stored before the user marked the match, which can
//    reappear stacked under a later allowed push of the same group (blocked pushes are never stored).
//    The spoken readout (talk) of a blocked push never runs.
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
//    who renders the row model, not observed on a device): the row model (EventRowModel, found by its
//    toString constant) is built in two places, the mapper from an Event and a copy that only swaps
//    the status text (the live minute tick). The mapper tells SpoilerFree.noteEvent the Event just
//    before it builds the model (the model carries only the event id, so the decision, marks and
//    status, is made and noted there per event id), and the model's only constructor starts with
//    SpoilerFree.maskRow(eventId, kickoff, status, home line, away line): for a row that hides its
//    score it nulls the three score fields of both team lines (found by type and order) and the
//    status text (live minute, "FT"), so the row shows no score. The team lines are built fresh on
//    every mapping, so a row mapped after full time keeps its score. The copy reuses the lines of the
//    row it copies and gets the same noted decision (an undecided id falls back to the match's own
//    mark). The kickoff time, team names and the extra info (postponed and the like) stay. The
//    switch is read in Java; the constructor has no free register.
// 5. Home-screen widget (sofascore.spoiler_widget): before the widget row builder returns, a hidden
//    match's score cells are emptied and its status cell shows the kickoff time. The widget redraws
//    on its own schedule, so a finished match shows its score at its next refresh.
// 6. Match page header (sofascore.spoiler_match_page, 2026-11-22): every concrete setEvent(Event)
//    below the header base (found by its kept setResultView; 26.11.09: football and generic sports,
//    tennis, volleyball, and the chat page's header) starts with SpoilerFree.onHeader, which makes
//    the result view (score, live minute, period scores) and the goal scorers / collapsed score
//    views (by id name) INVISIBLE while the page hides the score, and shows again only what it hid.
//    The activity calls setEvent with every match update, so full time shows the score at once;
//    setResultView hands a newly inflated result view the same decision; setFootballGoals is skipped
//    while hidden. Before kick-off the kickoff date stays (no score yet). The switch is read in Java.
// 7. Details tab (same switch): the three period score tables (by layout: details_table,
//    darts_table_score_view, details_table_cricket) start their bind with maskModule (GONE while
//    hidden) and their base's setVisibility override keeps a masked module GONE when the table sets
//    its own visibility; the period points graph (EventGraphPoint by period) is masked the same way.
// 8. Share modal (same switch): the share card's match header (share_match_details_modal_header, a
//    module of the same base) is GONE while hidden, so a shared image carries no score.
// Phase 3 (2026-11-23, same target 26.11.09). The match page surfaces below ask one page decision,
// SpoilerFree.pageHides(Context): the Context is unwrapped to its Activity, whose page subject (the
// Event noted by step 2 or 9) decides with the step 6 rule (sofascore.spoiler_match_page on,
// hidesScore, not "notstarted"). Surfaces that get the Event decide on it directly with the same rule.
// 9. Tabs (same switch): the tab builder (the EventHeadData observer, found by the AI tab's preference
//    keys and the EventHeadData component calls) notes the Event for the page right after
//    component1() (it may run before step 2's observer), and just before it hands the tab list to the
//    pager adapter, filterTabs removes the SPOILER_TAB_NAMES tabs (by enum constant name, in place:
//    the list is the builder's own ArrayList and is read again after the call) from a hidden page.
//    Details, the prematch tabs, odds, Standings, Knockout, Matches and Contribute stay (standings and
//    the cup tree can show the result of a finished match only, so they stay for now). The
//    navigateToTab lambda (the shared guard's site) asks blocksTab(tab, activity) right after it casts
//    its receiver to EventActivity, so a removed tab cannot be navigated to. The tabs come back at the
//    next EventHeadData emission after full time (inferred: the observer runs again on every refresh
//    of the match head; not observed).
// 10. Incidents (same switch): the incidents adapter's fill method starts with
//    incidents(adapter Context, list), which answers a new empty list for a hidden page (the stock list
//    may be the response's own list or an immutable empty one, so it is never changed); the period
//    grouping of its basketball/ice hockey subclasses (one (ArrayList, Event) method and its override)
//    starts with clearIfHidden(list, Event), which clears the ArrayList the caller has just built.
//    The goal rows, cards, period rows and running score are all in that list, so the Details tab of
//    the sports that use this adapter (football, basketball, ice hockey and the other users of the
//    incidents adapter and its subclasses) shows no incidents while hidden. Other sports' Details
//    adapters are not covered (see below).
// 11. Share modal incidents (same switch): ShareMatchDetailsModal (kept name) reads its incidents
//    through its only ()ArrayList method; every read is passed through shareIncidents(modal, list),
//    which answers a new empty list for a hidden match (the modal's Event is read through its only
//    ()Event method), so the share card has no goal rows or scorers (its momentum graph gets the
//    empty list too).
// 12. Search results (sofascore.spoiler_lists): before the search row binder returns (the one
//    (binding, I, Event)V method loading R.string.match_result_template), maskSearchScore makes the
//    score TextView (the receiver of the setText after the template) INVISIBLE for a hidden match.
//    The binder sets that view's visibility on every path of every bind, so a recycled row needs no
//    restore.
// Timing gap (steps 9 to 11): the tabs and the incidents change only when the app delivers new head
// data or new incidents, so marking a match on its open page, or a kick-off while the page is open,
// takes effect at the next such delivery, not at once (inferred; the page keeps what it last built).
// Un-marking a match whose page is open: its tabs and incidents come back only when the page is
// reopened (the swipe refresh is disabled in the tab builder).
// Not covered yet: in the match page, the esports Details game rows (an adapter filled through the
// adapter base's list setter from EsportsGamesResponse) and the other Details adapters built in
// EventDetailsFragment for tennis, volleyball, darts, table tennis, badminton, aussie rules and others
// (26.11.09: k17, zrh, bei, ot5; not traced), the baseball (Compose) and ice hockey period tables, the
// football momentum graph of the Details tab (its input was not traced; goal markers may remain), the Standings and
// Knockout tabs (a finished match's result only), featured players, the volleyball collapsed score
// (its visibility is reset by the app bar while scrolling), and chat message text; elsewhere
// favourites screens outside the Compose rows, featured match views (FeaturedMatchView), the basic
// event data and feed event cards (no Event-carrying producer was traced), team form (it lists
// finished matches, so it only matters with Keep final scores hidden), standings form, the ongoing
// match notification, the winner colour of the team names (list rows, widget and search) and the
// extra info text of a Compose row, Wear and lock-screen notifications. The tabs come back only at the
// next head refresh after full time, not at once. Which header, module, tab and adapter each sport
// uses is read from the code (26.11.09), not observed on a device. A Compose row kept in memory (the
// live status copy) shows scores again only after its list refreshes when the user un-marks a match.
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

    val header = matchHeader()
    HEADER_SCORE_IDS.forEach { resourceId(ResourceType.ID, it) }
    if (classDefBy(header.resultViewSetter.definingClass).methods.none { it.name == "getResultView" && it.params().isEmpty() }) {
        throw PatchException("${header.resultViewSetter.definingClass} has no getResultView(), SpoilerFree calls it")
    }
    (header.setEvents + header.resultViewSetter).forEach { it.requireNoBranchTargetAt(0, "Spoiler-free header hook") }
    header.footballGoals.requireLocals(1)
    header.footballGoals.requireNoBranchTargetAt(0, "Spoiler-free goals hook")

    val details = detailsModules()
    (details.tables + details.graph + details.setVisibility + details.share).forEach { it.requireNoBranchTargetAt(0, "Spoiler-free Details hook") }

    // Phase 3 sites.
    val tabEnumStrings = eventTabEnum().methods.single { it.name == "<clinit>" }.implementation!!.instructions
        .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
    SPOILER_TAB_NAMES.filterNot(tabEnumStrings::contains).takeIf { it.isNotEmpty() }?.let {
        throw PatchException("the match page tab enum has no constants $it")
    }
    val tabs = tabBuilder()
    tabs.method.requireNoBranchTargetAt(tabs.afterEvent, "Spoiler-free tab builder Event hook")
    val nav = navigateToTabSite(window = 12)
    val navAt = nav.activityCast + 1
    nav.method.requireNoBranchTargetAt(navAt, "Spoiler-free navigateToTab hook")
    if (nav.tabRegister > 15 || nav.activityRegister > 15) throw PatchException("navigateToTab: the tab or activity register is above v15")
    val navScratch = nav.method.findFreeRegister(navAt, nav.tabRegister, nav.activityRegister)
    if (navScratch > 255) throw PatchException("navigateToTab: no free register below v256")
    val (incidentsFill, adapterContext) = incidentsFill()
    incidentsFill.requireNoBranchTargetAt(0, "Spoiler-free incidents hook")
    val groupings = periodGroupings()
    groupings.forEach { it.requireNoBranchTargetAt(0, "Spoiler-free period incidents hook") }
    val shareReads = shareIncidentReads()
    val (searchRow, searchScore) = searchRow()
    val searchReturn = searchRow.instructions.indexOfFirst { it.opcode == Opcode.RETURN_VOID }

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

    // 6. Match page header. setEvent and setResultView call SpoilerFree with no gate (it reads the
    // switch itself, so turning the switch off shows a hidden header again at the next update);
    // setResultView has no local register for a gate. p0 and p1 are consecutive, so /range works.
    header.setEvents.forEach {
        it.addInstructionsChecked(0, "invoke-static/range { p0 .. p1 }, $SPOILER_FREE->onHeader(Ljava/lang/Object;Ljava/lang/Object;)V")
    }
    header.resultViewSetter.addInstructionsChecked(
        0,
        "invoke-static/range { p0 .. p1 }, $SPOILER_FREE->onResultView(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
    header.footballGoals.addHevalGate(SPOILER_MATCH_PAGE.id) { v ->
        """
            invoke-static/range { p0 .. p0 }, $SPOILER_FREE->hidesHeaderGoals(Ljava/lang/Object;)Z
            move-result $v
            if-eqz $v, :heval_stock
            return-void
        """
    }

    // 7. Details tab (same switch, read in Java): the module base's setVisibility passes its value
    // through moduleVisibility (a masked module stays GONE while the table binds and sets its own
    // visibility), and each score table's bind and the period points graph start with maskModule.
    details.setVisibility.addInstructionsChecked(
        0,
        """
            invoke-static/range { p0 .. p1 }, $SPOILER_FREE->moduleVisibility(Ljava/lang/Object;I)I
            move-result p1
        """,
    )
    (details.tables + details.graph).forEach {
        it.addInstructionsChecked(0, "invoke-static/range { p0 .. p1 }, $SPOILER_FREE->maskModule(Ljava/lang/Object;Ljava/lang/Object;)V")
    }
    // 8. The share modal's match header (same switch): its bind (?, Event) starts with maskShareHeader.
    details.share.addInstructionsChecked(
        0,
        "invoke-static/range { p0 .. p2 }, $SPOILER_FREE->maskShareHeader(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
    )

    // 9. Tabs (same switch, read in Java). The later site first, so the earlier index stays valid. The
    // tab list call takes over its labels (every path into it filters).
    tabs.method.addInstructionsAtControlFlowLabelChecked(
        tabs.setTabs,
        "invoke-static { v${tabs.activity}, v${tabs.tabs} }, $SPOILER_FREE->filterTabs(Ljava/lang/Object;$LIST)V",
    )
    tabs.method.addInstructionsChecked(
        tabs.afterEvent,
        "invoke-static { v${tabs.activity}, v${tabs.event} }, $SPOILER_FREE->onHeadData(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
    nav.method.addInstructionsWithLabelsChecked(
        navAt,
        """
            invoke-static { v${nav.tabRegister}, v${nav.activityRegister} }, $SPOILER_FREE->blocksTab(Ljava/lang/Enum;Ljava/lang/Object;)Z
            move-result v$navScratch
            if-eqz v$navScratch, :heval_stock
            sget-object v$navScratch, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            return-object v$navScratch
        """,
        ExternalLabel("heval_stock", nav.method.getInstruction(navAt)),
    )

    // 10. Incidents (same switch, read in Java): the fill method's list (p1) and the period groupings' ArrayList.
    incidentsFill.addInstructionsChecked(
        0,
        """
            iget-object v0, p0, ${adapterContext.smali}
            invoke-static { v0, p1 }, $SPOILER_FREE->incidents($CONTEXT$LIST)$LIST
            move-result-object p1
        """,
    )
    groupings.forEach {
        it.addInstructionsChecked(0, "invoke-static/range { p1 .. p2 }, $SPOILER_FREE->clearIfHidden($ARRAY_LIST${"Ljava/lang/Object;"})V")
    }

    // 11. The share modal's incident reads, the last read of each method first.
    shareReads.sortedByDescending { it.after }.forEach { read ->
        read.method.addInstructionsChecked(
            read.after,
            """
                invoke-static { v${read.modal}, v${read.list} }, $SPOILER_FREE->shareIncidents(Ljava/lang/Object;$ARRAY_LIST)$ARRAY_LIST
                move-result-object v${read.list}
            """,
        )
    }

    // 12. Search results (sofascore.spoiler_lists, read in Java): before the single return, which keeps
    // its labels on the inserted code. v0 and v1 are dead at the return.
    searchRow.addInstructionsAtControlFlowLabelChecked(
        searchReturn,
        """
            move-object/from16 v0, p1
            iget-object v0, v0, ${searchScore.smali}
            move-object/from16 v1, p3
            invoke-static { v0, v1 }, $SPOILER_FREE->maskSearchScore(Ljava/lang/Object;Ljava/lang/Object;)V
        """,
    )

    sofascoreControls.register(SPOILER_NOTIFICATIONS)
    sofascoreControls.register(SPOILER_LISTS)
    sofascoreControls.register(SPOILER_WIDGET)
    sofascoreControls.register(SPOILER_MATCH_PAGE)
    sofascoreControls.register(SPOILER_KEEP_FINAL_HIDDEN)
    sofascoreControls.register(SPOILER_EVENTS)
    sofascoreControls.register(SPOILER_COMPETITIONS)
}
