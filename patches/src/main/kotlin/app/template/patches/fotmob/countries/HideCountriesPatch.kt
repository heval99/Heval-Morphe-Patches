package app.template.patches.fotmob.countries

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.fotmob.controls.FotMobControlsCategory
import app.template.patches.fotmob.controls.fotMobActivityHooksPatch
import app.template.patches.fotmob.controls.fotMobControls
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import app.template.patches.shared.hevalcontrols.HevalListFeature
import app.template.patches.shared.hevalcontrols.featurePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val COUNTRY_FILTER = "Lapp/template/extension/fotmob/CountryFilter;"

/** Suggestion's list fields and the CountryFilter helper that filters each (players and referees stay). */
internal val SUGGESTION_FILTERS = mapOf(
    "leagueSuggestions" to "filterLeagueSuggestions",
    "teamSuggestions" to "filterTeamSuggestions",
    "matchesSuggestions" to "filterMatchSuggestions",
)

/**
 * The fields CountryFilter reads by name through reflection. FotMob keeps its model field names
 * (Gson and kotlinx models), but the patch checks them so a rename fails here, not silently at
 * runtime.
 */
internal val REFLECTED_FIELDS = mapOf(
    LIVE_MATCHES to listOf("leagueMatches"),
    "Lcom/fotmob/models/LeagueMatches;" to listOf("league", "Matches", "sortPriority"),
    "Lcom/fotmob/models/League;" to listOf("CountryCode", "Id", "ParentId", "CountryName", "isSuggested"),
    "Lcom/fotmob/models/Match;" to listOf("HomeTeam", "AwayTeam"),
    "Lcom/fotmob/models/Team;" to listOf("ID"),
    "Lcom/fotmob/models/search/LeagueSuggestions;" to listOf("leagues"),
    "Lcom/fotmob/models/search/TeamSuggestions;" to listOf("teams"),
    "Lcom/fotmob/models/search/MatchSuggestions;" to listOf("matches"),
    "Lcom/fotmob/models/search/LeagueSuggestion;" to listOf("nameAndId", "payload"),
    "Lcom/fotmob/models/search/TeamSuggestion;" to listOf("nameAndId", "payload"),
    "Lcom/fotmob/models/search/MatchSuggestion;" to listOf("payload"),
    "Lcom/fotmob/models/search/LeagueSuggestionPayload;" to listOf("countryCode"),
    "Lcom/fotmob/models/search/TeamSuggestionPayload;" to listOf("leagueId"),
    "Lcom/fotmob/models/search/MatchSuggestionPayload;" to listOf("leagueId", "homeTeamId", "awayTeamId"),
)

internal val HIDDEN_COUNTRIES = HevalListFeature(
    id = "fotmob.hidden_countries",
    name = "Hidden countries",
    description = "Leagues and matches from the countries you pick are hidden. Favourite leagues and teams stay.",
    category = FotMobControlsCategory.LEAGUES,
    restartRequired = true,
)

/** Passes register [register] through `CountryFilter.<helper>(Ljava/lang/Object;)...` before [index]. */
private fun MutableMethod.filterBefore(index: Int, register: Int, helper: String, castTo: String? = null) {
    val cast = castTo?.let { "\ncheck-cast v$register, $it" } ?: ""
    addInstructionsAtControlFlowLabelChecked(
        index,
        "invoke-static/range {v$register .. v$register}, $COUNTRY_FILTER->$helper\nmove-result-object v$register$cast",
    )
}

private fun BytecodePatchContext.requireReflectedFields() {
    REFLECTED_FIELDS.forEach { (type, names) ->
        val fields = classDefBy(type).fields.map { it.name }.toSet()
        names.filterNot(fields::contains).takeIf { it.isNotEmpty() }?.let { missing ->
            throw PatchException("Hide countries: $type no longer has the fields $missing")
        }
    }
}

// A Patch settings list (2026-11-03, owner request), opt-in in Manager. The keys are FotMob's
// league country codes (FIFA three-letter codes, "INT" national-team and "INT-2" club
// competitions); CountryFilter in extensions/fotmob does the filtering, the patch only routes
// three data paths through it:
//  1. match lists: the LiveMatches stored by LiveMatchesAndLeagueRank.<init> (a new LiveMatches
//     without hidden leagues; matches of favourite teams stay),
//  2. the leagues list: every List<League> LeagueConverter.convert returns (Leagues tab and
//     onboarding's league picker); it also reports the countries seen to the picker,
//  3. search: Suggestion's leagueSuggestions/teamSuggestions/matchesSuggestions in every
//     constructor that stores them (players and referees are not filtered).
// Favourite leagues and teams always stay. The picker is seeded from FotMobApp.onCreate through
// ActivityHooks.register (CountryFilter.init). The widget and notifications are not filtered.
@Suppress("unused")
val hideCountriesPatch = fotMobControls.featurePatch(
    HIDDEN_COUNTRIES,
    name = "Hide countries",
    description = "Hides the countries you pick in Patch settings from match lists, leagues and search.",
    default = false,
    dependencies = setOf(fotMobActivityHooksPatch),
) {
    classDefBy(COUNTRY_FILTER)
    requireReflectedFields()

    // 1. Match lists: filter the LiveMatches right before the constructor stores it.
    val holder = LiveMatchesAndLeagueRankInitFingerprint.method
    val store = LiveMatchesAndLeagueRankInitFingerprint.instructionMatches.single().index
    val liveMatches = holder.instructions[store].let { (it as TwoRegisterInstruction).registerA }
    holder.filterBefore(store, liveMatches, "filterLiveMatches(Ljava/lang/Object;)Ljava/lang/Object;", LIVE_MATCHES)

    // 2. Leagues list: filter every returned list.
    val convert = LeagueConverterFingerprint.method
    val returns = convert.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    if (returns.isEmpty()) throw PatchException("LeagueConverter.convert has no return-object")
    for (index in returns.reversed()) {
        val register = (convert.instructions[index] as OneRegisterInstruction).registerA
        convert.filterBefore(index, register, "filterLeagues(Ljava/util/List;)Ljava/util/List;")
    }

    // 3. Search: every store of the three filtered lists in every Suggestion constructor.
    val hooked = mutableSetOf<String>()
    for (constructor in mutableClassDefBy(SUGGESTION).methods.filter { it.name == "<init>" && it.implementation != null }) {
        val sites = constructor.instructions.withIndex().mapNotNull { (index, instruction) ->
            if (instruction.opcode != Opcode.IPUT_OBJECT) return@mapNotNull null
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            val helper = SUGGESTION_FILTERS[field.name]?.takeIf { field.definingClass == SUGGESTION } ?: return@mapNotNull null
            hooked += field.name
            Triple(index, (instruction as TwoRegisterInstruction).registerA, helper)
        }
        for ((index, register, helper) in sites.reversed()) {
            constructor.filterBefore(index, register, "$helper(Ljava/util/List;)Ljava/util/List;")
        }
    }
    if (hooked != SUGGESTION_FILTERS.keys) {
        throw PatchException("Suggestion: no constructor stores ${SUGGESTION_FILTERS.keys - hooked}")
    }
}
