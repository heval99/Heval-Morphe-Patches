package app.template.patches.wemuslim.layout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.template.patches.shared.Constants.COMPATIBILITY_WEMUSLIM
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.wemuslim.misc.encryption.bypassNativeEncryptionPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val ME_BINDING = "Lcom/fyxtech/muslim/bizme/databinding/MeFragmentMainBinding;"
private const val CARD_VIEW = "Landroidx/cardview/widget/CardView;"
private const val VIEW_HIDER_TYPE = "Lapp/template/extension/wemuslim/ViewHider;"
internal const val ME_SETTINGS_ROUTE = "me/setting"
internal const val ME_USER_HEADER =
    "Lcom/fyxtech/muslim/about/widget/CollapsingMeasureHeightConstraintLayout;"

// The Me tab's view model constructor (R8-renamed, `aoa` in 3.5.5; MeMainViewModel in its coroutine
// metadata): it fills the menu list with one ArrayList.add per row, each row named by its router path.
// Three of the paths are enough to tell it from the router table that also holds "me/setting".
internal object MeMenuRowsFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(),
    strings = listOf(ME_SETTINGS_ROUTE, "miniapp/list", "wallet/donation")
        .map { string(it, StringComparisonType.EQUALS) },
    custom = { method, _ -> method.implementation != null },
)

// me_fragment_main's recyclerviewContent shows the rows of that list (Favorites, Mini Programs, Support
// Us, Rate Us, Invite Friends, Gift Giving Box, FAQ & Feedback, Settings) in one rounded card. Dropping
// the rows from the list itself leaves no gaps and the card shrinks to the one Settings row. The list is
// only searched by row type, never by a fixed position; the Me binding has no Messages row (the list has
// no messages entry in 3.5.5). Only the adds to the list stored into the view model's ArrayList field are
// dropped. The login / profile header is the kept CollapsingMeasureHeightConstraintLayout
// (userLoginContainer: avatar, name or "Log in" line), forced GONE like VipCardView; the toolbar then
// collapses to its minimum height, so the status bar clearance stays, and the collapsed header
// (collapsedUserLoginContainer, GONE and only shown while the toolbar scrolls) never appears. The
// "Log in" prompt card (cvLoginPrompt) is shown again by account-state code, so it is taken out of its
// parent in MeFragmentMainBinding's constructor (the only CardView parameter) through
// extensions/wemuslim ViewHider.remove: later show calls then change nothing. VipCardView
// (membership card) is not touched here; "Hide premium banners" hides it.
@Suppress("unused")
val hideMeTabRowsPatch = bytecodePatch(
    name = "Hide Me tab rows",
    description = "Hides the Me tab's rows, profile header and login prompt, leaving only Settings.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WEMUSLIM)

    // The friends, badge and shortcut cards are hidden too (a logged-in profile shows them).
    dependsOn(bypassNativeEncryptionPatch, hideMeTabExtrasPatch)

    extendWith("extensions/wemuslim.mpe")

    execute {
        // 1. The menu list: only the ArrayList.add of the "me/setting" row stays.
        val constructor = MeMenuRowsFingerprint.method
        val instructions = constructor.instructions.toList()
        val routeIndex = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == ME_SETTINGS_ROUTE
        }
        if (routeIndex < 0) throw PatchException("Hide Me tab rows: no \"$ME_SETTINGS_ROUTE\" in ${constructor.definingClass}")
        // The register that is stored into the class's own ArrayList field (the Me rows list).
        val listRegister = instructions.firstNotNullOfOrNull {
            val field = (it as? ReferenceInstruction)?.reference as? FieldReference
            if (it.opcode == Opcode.IPUT_OBJECT && field?.definingClass == constructor.definingClass &&
                field.type == "Ljava/util/ArrayList;"
            ) (it as OneRegisterInstruction).registerA else null
        } ?: throw PatchException("Hide Me tab rows: ${constructor.definingClass} stores no ArrayList field")
        val adds = instructions.indices.filter {
            val call = (instructions[it] as? ReferenceInstruction)?.reference as? MethodReference
            instructions[it].opcode == Opcode.INVOKE_VIRTUAL && call?.definingClass == "Ljava/util/ArrayList;" &&
                call.name == "add" && call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") &&
                (instructions[it] as FiveRegisterInstruction).registerC == listRegister
        }
        val keep = adds.firstOrNull { it > routeIndex }
            ?: throw PatchException("Hide Me tab rows: no ArrayList.add after \"$ME_SETTINGS_ROUTE\"")
        val drop = adds.filter { it != keep }
        if (drop.size < 3) throw PatchException("Hide Me tab rows: only ${drop.size} other rows in ${constructor.definingClass}")
        for (index in drop) {
            // The result of add() is not read (no move-result follows).
            if (instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT) {
                throw PatchException("Hide Me tab rows: the add() result at $index is read")
            }
        }
        // Descending, so a removal never shifts an index still to be visited.
        for (index in drop.reversed()) constructor.removeInstruction(index)

        // 2. The login / profile header.
        forceViewGone(ME_USER_HEADER, "Hide Me tab rows")

        // 3. The "Log in" prompt card: removed from its parent when the binding is built. Its constructor
        // parameter is the only CardView; p-registers keep their real index, so a range invoke is used.
        val binding = mutableClassDefByOrNull(ME_BINDING) ?: throw PatchException("Hide Me tab rows: $ME_BINDING not found")
        val bindingConstructor = binding.methods.singleOrNull {
            it.name == "<init>" && it.implementation != null &&
                it.parameterTypes.count { type -> type.toString() == CARD_VIEW } == 1
        } ?: throw PatchException("Hide Me tab rows: $ME_BINDING has no constructor with one CardView")
        val cardIndex = bindingConstructor.parameterTypes.indexOfFirst { it.toString() == CARD_VIEW }
        fun width(type: CharSequence) = if (type.toString() in setOf("J", "D")) 2 else 1
        val parameterRegisters = bindingConstructor.parameterTypes.sumOf(::width)
        val thisRegister = bindingConstructor.implementation!!.registerCount - parameterRegisters - 1
        val cardRegister = thisRegister + 1 + bindingConstructor.parameterTypes.take(cardIndex).sumOf(::width)
        bindingConstructor.addInstructionsChecked(
            0,
            "invoke-static/range {v$cardRegister .. v$cardRegister}, $VIEW_HIDER_TYPE->remove(Landroid/view/View;)V",
        )
    }
}
