package app.template.patches.brave.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.string
import app.template.patches.brave.userscripts.PageMenuBuilderFingerprint
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.ReferenceUtil

// Ids of the page menu rows to drop (resource names are kept in 1.100.58: id/brave_wallet_id ...).
private val BLOAT_MENU_IDS = listOf(
    "brave_wallet_id",
    "brave_leo_id",
    "brave_rewards_id",
    "brave_news_id",
    "request_brave_vpn_check_id",
    "request_brave_vpn_id",
    "request_brave_vpn_row_menu_id",
    "request_vpn_location_id",
)

// Settings rows (preference keys) to drop; "brave_rewards" has no Java key in 1.100.58.
private val BLOAT_PREFERENCE_KEYS = listOf(
    "brave_news_v2",
    "brave_wallet",
    "brave_vpn",
    "brave_vpn_callout",
    "brave_leo",
)

// BraveMainPreferencesBase.updatePreferences-style method (g4 in 1.100.58): the only one that names
// "brave_vpn_callout" and "features_section"; Brave removes its own conditional rows here.
internal object MainPreferencesUpdateFingerprint : Fingerprint(
    definingClass = "Lorg/chromium/chrome/browser/settings/BraveMainPreferencesBase;",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("brave_vpn_callout"),
        string("features_section"),
    ),
)

@Suppress("unused")
val stripMenuSettingsBloatPatch = bytecodePatch(
    name = "Strip menu and settings bloat",
    description = "Hides Wallet, Leo, News and VPN from the menu and settings, and Rewards from the menu.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    execute {
        // ── 1. Page (three dots) menu: remove the rows by id before the model is returned ───
        //  The menu class has a static (model, int[])V that deletes the rows with the given ids;
        //  it is called here with the ids of the bloat rows (looked up by name).
        val menu = PageMenuBuilderFingerprint.method
        val menuClass = PageMenuBuilderFingerprint.classDef
        val remover = menuClass.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.implementation != null &&
                it.parameterTypes.map(CharSequence::toString) == listOf(menu.returnType, "[I")
        }.singleOrNull() ?: throw PatchException("${menu.name}: no single static (model, int[])V row remover")

        val ids = BLOAT_MENU_IDS.map { resourceId(ResourceType.ID, it).toInt() }
        val insns = menu.instructions.toList()
        val returns = insns.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        if (returns.isEmpty()) throw PatchException("${menu.name}: no return-object")

        val impl = menu.implementation!!
        val addresses = ArrayList<Int>(insns.size)
        var address = 0
        insns.forEach { addresses += address; address += it.codeUnits }

        returns.asReversed().forEach { (index, insn) ->
            val model = (insn as OneRegisterInstruction).registerA
            if (impl.tryBlocks.any { addresses[index] >= it.startCodeAddress && addresses[index] < it.startCodeAddress + it.codeUnitCount }) {
                throw PatchException("${menu.name}: the return at $index sits inside a try block")
            }
            // Three scratch registers that are not the model (dead before the return) and fit 4 bits.
            val scratch = (0..15).filter { it != model }.take(3)
            val localCount = impl.registerCount - menu.parameterTypes.size - 1
            if (model >= 16 || scratch.size < 3 || scratch.any { it >= localCount }) {
                throw PatchException("${menu.name}: no usable registers (model v$model, locals $localCount)")
            }
            val (array, size, value) = scratch
            val fill = ids.mapIndexed { position, id ->
                """
                    const/16 v$size, $position
                    const v$value, $id
                    aput v$value, v$array, v$size
                """
            }.joinToString("\n")
            menu.addInstructionsAtControlFlowLabelChecked(
                index,
                """
                    const/16 v$size, ${ids.size}
                    new-array v$array, v$size, [I
                    $fill
                    invoke-static {v$model, v$array}, ${ReferenceUtil.getReferenceString(remover)}
                """,
            )
        }

        // ── 2. Settings: remove the rows at the end of the screen's own update method ──────
        //  Brave removes its conditional rows with a (String)V method on this fragment; that method is
        //  read from the calls in the update method and called for each bloat key. A removed row is
        //  still found by Brave's own findPreference (it keeps a map of removed rows), so later
        //  lookups of these keys do not hit null.
        val prefs = MainPreferencesUpdateFingerprint.method
        val prefInsns = prefs.instructions.toList()
        // Brave's own "remove this preference by key" method on the fragment: the only (String)V of the
        // class that moves the Preference into a HashMap (so findPreference still finds it) after taking it
        // off the PreferenceScreen.
        val removerCall = MainPreferencesUpdateFingerprint.classDef.methods.filter { method ->
            val refs = method.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .orEmpty()
            method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                refs.any { it.definingClass == "Ljava/util/HashMap;" && it.name == "put" } &&
                refs.any { it.returnType == "Landroidx/preference/PreferenceScreen;" }
        }.singleOrNull() ?: throw PatchException("${prefs.name}: no single preference remover (String)V on the fragment")
        val prefImpl = prefs.implementation!!
        val prefLocals = prefImpl.registerCount - 1
        if (prefLocals < 2) throw PatchException("${prefs.name}: needs two free local registers")
        val keyCalls = BLOAT_PREFERENCE_KEYS.joinToString("\n") {
            """
                const-string v1, "$it"
                invoke-virtual {v0, v1}, ${ReferenceUtil.getReferenceString(removerCall)}
            """
        }
        prefInsns.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }.asReversed().forEach { index ->
            prefs.addInstructionsAtControlFlowLabelChecked(
                index,
                """
                    move-object/from16 v0, p0
                    $keyCalls
                """,
            )
        }
    }
}
