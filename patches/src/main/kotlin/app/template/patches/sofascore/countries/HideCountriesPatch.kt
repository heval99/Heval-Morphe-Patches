package app.template.patches.sofascore.countries

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.hevalcontrols.HevalListFeature
import app.template.patches.shared.hevalcontrols.featurePatch
import app.template.patches.shared.requireNoBranchTargetAt
import app.template.patches.sofascore.controls.SofascoreControlsCategory
import app.template.patches.sofascore.controls.sofascoreControls
import app.template.patches.sofascore.misc.animations.ReleaseAppOnCreateFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

internal const val COUNTRY_FILTER = "Lapp/template/extension/sofascore/CountryFilter;"

private const val MODEL = "Lcom/sofascore/model/mvvm/model/"

/**
 * The getters CountryFilter calls by name through reflection. Sofascore's models are
 * kotlinx-serialization classes whose names and getters R8 keeps, but the patch checks them so a
 * rename fails here, not silently at runtime.
 */
internal val REFLECTED_METHODS = mapOf(
    "${MODEL}Category;" to listOf("getAlpha2", "getSlug", "getName"),
    "Lcom/sofascore/model/newNetwork/CategoryWrapper;" to listOf("getCategory"),
    "Lcom/sofascore/model/network/response/SearchEntity;" to listOf("getType", "getEntity"),
    "${MODEL}Event;" to listOf("getTournament"),
    "${MODEL}Tournament;" to listOf("getCategory"),
    "${MODEL}UniqueTournament;" to listOf("getCategory"),
    "${MODEL}UniqueStage;" to listOf("getCategory"),
    "${MODEL}Team;" to listOf("getCategory", "getCountry"),
    "${MODEL}Country;" to listOf("getAlpha2", "getSlug", "getName"),
    "${MODEL}Player;" to listOf("getTeam"),
    EVENT_LIST_RESPONSE to listOf("getEvents"),
)

internal const val EVENT_LIST_RESPONSE = "Lcom/sofascore/model/newNetwork/EventListResponse;"

/** The getter return filters: fingerprint, CountryFilter helper. */
private val GETTER_FILTERS = listOf(
    SportCategoriesGetterFingerprint to "filterCategories",
    CategoriesForDateGetterFingerprint to "filterCategoryWrappers",
    SearchResultsGetterFingerprint to "filterSearchResults",
)

/** The event list requests whose result is filtered: fingerprint, NetworkCoroutineAPI method. */
private val EVENT_LIST_REQUESTS = listOf(
    SportLiveEventsCallFingerprint to "sportLiveEvents",
    IndexedEventsPageCallFingerprint to "getIndexedEventsPage",
)

internal val HIDDEN_COUNTRIES = HevalListFeature(
    id = "sofascore.hidden_countries",
    name = "Hidden countries",
    description = "Leagues and matches from the countries you pick are hidden, favourites too.",
    category = SofascoreControlsCategory.LEAGUES,
    restartRequired = true,
)

private fun BytecodePatchContext.requireReflectedMethods() {
    REFLECTED_METHODS.forEach { (type, names) ->
        val methods = classDefBy(type).methods.map { it.name }.toSet()
        names.filterNot(methods::contains).takeIf { it.isNotEmpty() }?.let { missing ->
            throw PatchException("Hide countries: $type no longer has the methods $missing")
        }
    }
    classDefBy(EVENT_LIST_RESPONSE).methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;")
    } ?: throw PatchException("Hide countries: $EVENT_LIST_RESPONSE has no <init>(List)")
}

private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The registers an invoke passes, in order. */
private fun Instruction.invokeRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/**
 * The string constant in [register] right before instruction [before] of this straight-line
 * method, following `move-object`s; null when the register holds anything else.
 */
private fun List<Instruction>.stringIn(register: Int, before: Int): String? {
    var wanted = register
    for (i in before - 1 downTo 0) {
        val instruction = this[i]
        if (!instruction.opcode.setsRegister()) continue
        val target = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        if (instruction.opcode.setsWideRegister()) {
            if (wanted == target || wanted == target + 1) return null
            continue
        }
        if (target != wanted) continue
        when (instruction.opcode) {
            Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                return ((instruction as ReferenceInstruction).reference as StringReference).string
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                wanted = (instruction as TwoRegisterInstruction).registerB
            else -> return null
        }
    }
    return null
}

/**
 * The app's country list, code to name, read from the Country constructor calls of [this]
 * <clinit>. Every Country constructor the list uses takes (mcc or mcc list, iso2Alpha, iso3Alpha,
 * ioc, name, ...); a code or name that is not a string constant is skipped.
 */
internal fun Method.countrySeeds(): Map<String, String> {
    val instructions = implementation?.instructions?.toList() ?: return emptyMap()
    val seeds = LinkedHashMap<String, String>()
    instructions.forEachIndexed { index, instruction ->
        val reference = instruction.methodReference() ?: return@forEachIndexed
        if (reference.definingClass != COUNTRY || reference.name != "<init>") return@forEachIndexed
        val parameters = reference.parameterTypes.map(CharSequence::toString)
        if (parameters.size < 5 || parameters[0] !in setOf("I", "Ljava/util/List;") ||
            parameters.subList(1, 5).any { it != "Ljava/lang/String;" }
        ) return@forEachIndexed
        val registers = instruction.invokeRegisters()
        if (registers.size < 6) return@forEachIndexed
        val code = instructions.stringIn(registers[2], index) ?: return@forEachIndexed
        val name = instructions.stringIn(registers[5], index) ?: return@forEachIndexed
        if (code.isEmpty() || code.length > 100 || name.isBlank() || code.any { it == '\t' || it == '\n' } ||
            name.any { it == '\t' || it == '\n' }
        ) return@forEachIndexed
        seeds.putIfAbsent(code, name.trim())
    }
    return seeds
}

/** Rewrites the String getter [this] (CountryFilter.seeds) to return [value]. */
private fun MutableMethod.returnString(value: String) {
    val implementation = implementation ?: throw PatchException("$definingClass->$name has no code")
    if (returnType != "Ljava/lang/String;" || implementation.registerCount < 1) {
        throw PatchException("$definingClass->$name is not a String getter with a register")
    }
    implementation.addInstruction(0, BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference(value)))
    implementation.addInstruction(1, BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
}

/** The single return-object of a model getter. */
private fun MutableMethod.getterReturn(): Int {
    val returns = instructions.indices.filter { getInstruction(it).opcode == Opcode.RETURN_OBJECT }
    return returns.singleOrNull() ?: throw PatchException("$definingClass->$name has ${returns.size} return-object")
}

/**
 * The return-object of the request lambda's [api] case: the first one after the call, checked to
 * return the call's result register (the resume path returns the same register).
 */
private fun MutableMethod.requestReturn(api: String): Int {
    val calls = instructions.indices.filter {
        getInstruction(it).methodReference()?.let { ref -> ref.definingClass == NETWORK_COROUTINE_API && ref.name == api } == true
    }
    val call = calls.singleOrNull()
        ?: throw PatchException("$definingClass->$name calls $api ${calls.size} times")
    val move = getInstruction(call + 1)
    if (move.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("$api's result is not moved right after the call")
    val register = (move as OneRegisterInstruction).registerA
    val ret = (call + 2 until instructions.size).firstOrNull { getInstruction(it).opcode == Opcode.RETURN_OBJECT }
        ?: throw PatchException("$definingClass->$name has no return-object after $api")
    if ((getInstruction(ret) as OneRegisterInstruction).registerA != register) {
        throw PatchException("$definingClass->$name does not return $api's result")
    }
    return ret
}

/** `vX = CountryFilter.<helper>(vX)` before instruction [index]. */
private fun filterSmali(register: Int, helper: String) =
    "invoke-static/range {v$register .. v$register}, $COUNTRY_FILTER->$helper\nmove-result-object v$register"

// A Patch settings list (2026-11-03, owner request), opt-in in Manager. The keys are the categories'
// alpha2 codes (Sofascore's own: EN England, SX Scotland, WA Wales, NX Northern Ireland, XK Kosovo,
// ...); categories without one (World, Europe, International, tours) are "slug:<slug>". CountryFilter
// in extensions/sofascore does the filtering; the patch routes five data paths through it:
//  1. the leagues list: SportCategoriesResponse.getCategories (never emptied),
//  2. the date feed's country rows: CategoriesForDateResponse.getCategories (the categories of a
//     date and the category query),
//  3. search: SearchResponse.getResults (events, competitions, teams and players),
//  4. the Live tab: the sportLiveEvents request's result (a new EventListResponse),
//  5. the chronological view: the getIndexedEventsPage request's result (the same).
// Not filtered: LiveCategoriesResponse.getLiveCategories (a category id -> live count map with no
// country; the rows it counts are filtered by 2), pinned/favourite leagues fetched by their own
// tournament requests, team/player/tournament pages, the widget and notifications. Favourites are
// not exempt in the filtered lists. The picker is seeded with the app's own country list (read at
// patch time) by CountryFilter.init at the end of ReleaseApp.onCreate; every filtered category is
// reported as seen, also while nothing is picked.
@Suppress("unused")
val hideCountriesPatch = sofascoreControls.featurePatch(
    HIDDEN_COUNTRIES,
    name = "Hide countries",
    description = "Hides the countries you pick in Patch settings from match lists, leagues and search.",
    default = false,
) {
    val filter = mutableClassDefBy(COUNTRY_FILTER)
    requireReflectedMethods()

    // Validate every site before the first edit.
    val seeds = CountryListFingerprint.method.countrySeeds()
    if (seeds.size < 100 || listOf("EN", "SX", "WA", "NX").any { it !in seeds }) {
        throw PatchException("Hide countries: the app's country list gave ${seeds.size} countries")
    }
    val getters = GETTER_FILTERS.map { (fingerprint, helper) ->
        val method = fingerprint.method
        val ret = method.getterReturn()
        method.requireNoBranchTargetAt(ret, "Hide countries $helper")
        Triple(method, ret, helper)
    }
    val requests = EVENT_LIST_REQUESTS.map { (fingerprint, api) ->
        val method = fingerprint.method
        method to method.requestReturn(api)
    }
    if (requests.map { it.first }.toSet().size != requests.size) {
        throw PatchException("Hide countries: the live and chronological requests share a method")
    }
    val onCreate = ReleaseAppOnCreateFingerprint.method
    val onCreateReturns = onCreate.instructions.indices.filter { onCreate.getInstruction(it).opcode == Opcode.RETURN_VOID }
    if (onCreateReturns.isEmpty()) throw PatchException("ReleaseApp.onCreate has no return-void")

    // 0. Picker seeds: the app's country list into CountryFilter.seeds(), registered at startup.
    filter.methods.single { it.name == "seeds" && it.parameterTypes.isEmpty() }
        .returnString(seeds.entries.joinToString("\n") { (code, name) -> "$code\t$name" })
    onCreateReturns.sortedDescending().forEach { ret ->
        onCreate.addInstructionsAtControlFlowLabelChecked(ret, "invoke-static {}, $COUNTRY_FILTER->init()V")
    }

    // 1-3. vX = helper(vX); return-object vX in the model getters.
    for ((method, ret, helper) in getters) {
        val register = (method.getInstruction(ret) as OneRegisterInstruction).registerA
        method.addInstructionsChecked(ret, filterSmali(register, "$helper(Ljava/util/List;)Ljava/util/List;"))
    }

    // 4-5. The request lambdas' return: labels move to the filter, so the direct and the resumed
    //      path both pass through it.
    for ((method, ret) in requests) {
        val register = (method.getInstruction(ret) as OneRegisterInstruction).registerA
        method.addInstructionsAtControlFlowLabelChecked(ret, filterSmali(register, "filterEventList(Ljava/lang/Object;)Ljava/lang/Object;"))
    }
}
