package app.template.patches.sofascore.countries

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.addHevalGate
import app.template.patches.shared.requireNoBranchTargetAt
import app.template.patches.sofascore.controls.SofascoreControlsCategory
import app.template.patches.sofascore.spoiler.LEAGUE_ACTIVITY
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val QUICK_HIDE_CLASS = "Lapp/template/extension/sofascore/QuickHide;"

/** The Leagues tab's country row layout (26.11.09: inflated by gl3.h, bound by ze4.e). */
internal const val COUNTRY_ROW_LAYOUT = "category_header_cell"

/** The row model's toString prefix; two models share it (26.11.09: af4 in Leagues, ov7 in the date feed). */
internal const val COLLAPSIBLE_CATEGORY_PREFIX = "CollapsibleCategory(category="

internal const val VIEW_HOLDER = "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;"
private const val CATEGORY = "Lcom/sofascore/model/mvvm/model/Category;"
private const val UNIQUE_TOURNAMENT = "Lcom/sofascore/model/mvvm/model/UniqueTournament;"
private const val MENU = "Landroid/view/Menu;"
private const val VIEW = "Landroid/view/View;"

/**
 * "Hide from pages" (owner 2026-11-23): hide actions on a competition's page and on the Leagues
 * tab's country rows. Off by default (a mistaken tap would hide a list entry); each action also
 * asks for confirmation. No dependency on either list: it is registered whenever one of them is in,
 * and QuickHide offers only the actions whose list patch is in.
 */
internal val QUICK_HIDE = HevalFeature(
    id = "sofascore.quick_hide",
    name = "Hide from pages",
    description = "Adds Hide competition and Hide country actions to league pages and the Leagues tab.",
    category = SofascoreControlsCategory.LEAGUES,
    default = false,
    icon = "eye_off",
)

/** The hook sites, found before the first edit. */
internal class QuickHideSites(
    val menu: MutableMethod,
    val setter: MutableMethod,
    val row: MutableMethod,
    val rowModel: String,
    val rowCategory: FieldReference,
)

private fun Method.params() = parameterTypes.map(CharSequence::toString)
private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.typeRef() =
    ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.fieldRef() =
    (this as? ReferenceInstruction)?.reference as? FieldReference

/** Locals of [this] (registers that are not parameters); throws when there are fewer than [count]. */
private fun MutableMethod.requireLocals(count: Int, what: String) {
    val locals = implementation!!.registerCount - (params().sumOf { if (it == "J" || it == "D") 2 else 1 } + if (isStatic()) 0 else 1)
    if (locals < count) throw PatchException("Hide from pages: $definingClass->$name has $locals locals, the $what needs $count")
}

/**
 * The Leagues tab's country row binder (26.11.09: ze4.e(IILjava/lang/Object;)V): the instance
 * (I, I, Object)V method of a RecyclerView.ViewHolder subclass that holds the binding inflated from
 * [COUNTRY_ROW_LAYOUT], casts its item to a CollapsibleCategory model and reads the model's only
 * Category field. Exactly one such method must exist.
 */
private fun BytecodePatchContext.countryRow(): Triple<MutableMethod, String, FieldReference> {
    val layout = resourceId(ResourceType.LAYOUT, COUNTRY_ROW_LAYOUT).toInt()
    val supers = mutableMapOf<String, String?>()
    val bindings = mutableSetOf<String>()
    val models = mutableMapOf<String, FieldReference>()
    classDefForEach { classDef ->
        supers[classDef.type] = classDef.superclass
        classDef.methods.forEach { method ->
            // The binding's inflater: static (LayoutInflater, ViewGroup) -> binding, loading the layout.
            if (method.isStatic() && method.params() == listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;") &&
                method.returnType.startsWith("L") &&
                method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == layout } == true
            ) bindings += method.returnType
        }
        classDef.collapsibleCategoryField()?.let { models[classDef.type] = it }
    }
    val binding = bindings.singleOrNull()
        ?: throw PatchException("Hide from pages: ${bindings.size} bindings inflate $COUNTRY_ROW_LAYOUT ($bindings), expected one")
    if (models.isEmpty()) throw PatchException("Hide from pages: no $COLLAPSIBLE_CATEGORY_PREFIX model with one Category field")

    fun isHolder(type: String): Boolean {
        var current = supers[type]
        while (current != null) {
            if (current == VIEW_HOLDER) return true
            current = supers[current]
        }
        return false
    }

    data class Found(val type: String, val method: Method, val model: String)
    val found = mutableListOf<Found>()
    classDefForEach { classDef ->
        if (!isHolder(classDef.type)) return@classDefForEach
        if (classDef.fields.none { it.type == binding && !AccessFlags.STATIC.isSet(it.accessFlags) }) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.returnType != "V" || method.isStatic() || method.params() != listOf("I", "I", "Ljava/lang/Object;")) return@forEach
            val insns = method.implementation?.instructions?.toList() ?: return@forEach
            val cast = insns.mapNotNull { if (it.opcode == Opcode.CHECK_CAST) it.typeRef() else null }.filter { it in models }.distinct()
            val model = cast.singleOrNull() ?: return@forEach
            val field = models.getValue(model)
            if (insns.none { it.opcode == Opcode.IGET_OBJECT && it.fieldRef()?.let { f -> f.definingClass == field.definingClass && f.name == field.name } == true }) {
                return@forEach
            }
            found += Found(classDef.type, method, model)
        }
    }
    val row = found.singleOrNull()
        ?: throw PatchException("Hide from pages: ${found.size} country row binders (${found.map { "${it.type}->${it.method.name}" }}), expected one")
    val method = mutableClassDefBy(row.type).methods.single {
        it.name == row.method.name && it.returnType == "V" && it.params() == row.method.params()
    }
    return Triple(method, row.model, models.getValue(row.model))
}

/** The only instance Category field of a class whose toString starts with [COLLAPSIBLE_CATEGORY_PREFIX], else null. */
private fun ClassDef.collapsibleCategoryField(): FieldReference? {
    val toString = methods.singleOrNull { it.name == "toString" && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
        ?: return null
    val first = toString.implementation?.instructions?.firstOrNull { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
    val text = ((first as? ReferenceInstruction)?.reference as? StringReference)?.string ?: return null
    if (!text.startsWith(COLLAPSIBLE_CATEGORY_PREFIX)) return null
    return fields.filter { it.type == CATEGORY && !AccessFlags.STATIC.isSet(it.accessFlags) }.singleOrNull()
}

/** Finds and checks every "Hide from pages" site; edits nothing. */
internal fun BytecodePatchContext.quickHideSites(): QuickHideSites {
    classDefBy(QUICK_HIDE_CLASS)
    if (classDefBy(VIEW_HOLDER).fields.none { it.name == "itemView" && it.type == VIEW && !AccessFlags.STATIC.isSet(it.accessFlags) }) {
        throw PatchException("Hide from pages: $VIEW_HOLDER has no itemView field")
    }
    val league = mutableClassDefBy(LEAGUE_ACTIVITY)
    // onCreateOptionsMenu is the framework override, so its name is stable.
    val menus = league.methods.filter { it.name == "onCreateOptionsMenu" && it.params() == listOf(MENU) && it.returnType == "Z" && it.implementation != null }
    val menu = menus.singleOrNull() ?: throw PatchException("Hide from pages: $LEAGUE_ACTIVITY has ${menus.size} onCreateOptionsMenu(Menu)Z")
    val setters = league.methods.filter { it.returnType == "V" && it.params() == listOf(UNIQUE_TOURNAMENT) && !it.isStatic() && it.implementation != null }
    val setter = setters.singleOrNull() ?: throw PatchException("Hide from pages: $LEAGUE_ACTIVITY has ${setters.size} (UniqueTournament)V methods, expected one")
    listOf(menu, setter).forEach {
        it.requireLocals(1, "gate")
        it.requireNoBranchTargetAt(0, "Hide from pages")
    }
    val (row, model, field) = countryRow()
    row.requireLocals(3, "row hook")
    row.requireNoBranchTargetAt(0, "Hide from pages row hook")
    return QuickHideSites(menu, setter, row, model, field)
}

/**
 * The edits. The page hooks are gates at index 0 like Spoiler-free's (which hooks the same two
 * methods): whichever patch runs second puts its gate in front of the other's, and each gate falls
 * through into the next, so both orders work. The row hook is not gated (QuickHide reads the switch
 * on every bind, so a row bound while it was on loses its long press once it is off).
 */
internal fun QuickHideSites.apply() {
    menu.addHevalGate(QUICK_HIDE.id) {
        "invoke-static/range { p0 .. p1 }, $QUICK_HIDE_CLASS->addLeagueItems(Ljava/lang/Object;$MENU)V"
    }
    setter.addHevalGate(QUICK_HIDE.id) {
        "invoke-static/range { p0 .. p1 }, $QUICK_HIDE_CLASS->onLeague(Ljava/lang/Object;Ljava/lang/Object;)V"
    }
    // v0..v2 are locals (checked) and hold nothing at entry; p3 is the bound item.
    row.addInstructionsChecked(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, $VIEW_HOLDER->itemView:$VIEW
            move-object/from16 v1, p3
            instance-of v2, v1, $rowModel
            if-eqz v2, :quick_hide_other_row
            check-cast v1, $rowModel
            iget-object v1, v1, $rowModel->${rowCategory.name}:$CATEGORY
            goto :quick_hide_call
            :quick_hide_other_row
            const/4 v1, 0x0
            :quick_hide_call
            invoke-static { v0, v1 }, $QUICK_HIDE_CLASS->onCountryRow(${VIEW}Ljava/lang/Object;)V
        """,
    )
}
