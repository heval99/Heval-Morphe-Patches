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
import app.template.patches.shared.addInstructionsWithLabelsChecked
import app.template.patches.wemuslim.misc.encryption.bypassNativeEncryptionPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val NAV_LAYOUT = "Lcom/fyxtech/muslim/widget/mainnavigation/MainBottomNavigationLayout;"
private const val NAV_BINDING = "Lcom/fyxtech/muslim/databinding/MainLayoutBottomNavigationBinding;"
private const val MESSAGE_TAB_PAGE = "messageTab"
private const val RED_DOT_FIELD = "msgMainTabRedDotView"
private const val VIEW_HIDER = "Lapp/template/extension/wemuslim/ViewHider;"

// Tab type 3 of the bottom bar's tab model (R8-renamed, `ecc` in 3.5.5): 0 home, 1 worship,
// 2 ummah, 3 message, 4 me. The model has one method that turns the type into those five strings,
// which is how the class is found.
internal object NavigationTabTypeFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("home", "worship", "ummah", "message", "me")
        .map { string(it, StringComparisonType.EQUALS) },
)

// MainActivity's pager builder: it adds the TabEntity pages one by one and takes the strings of the
// lateinit fields in its null checks ("homeTab", "prayerTab", "ummahTab", "messageTab", "meTab").
internal object PagerBuilderFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(string(MESSAGE_TAB_PAGE, StringComparisonType.EQUALS)),
    custom = { method, _ -> method.implementation != null },
)

// The unread-count setter of the bottom bar: (int count, boolean) on the kept layout class, the
// only such method that touches the Messages red dot (msgMainTabRedDotView, a kept binding field).
internal object MessagesRedDotSetterFingerprint : Fingerprint(
    definingClass = NAV_LAYOUT,
    returnType = "V",
    parameters = listOf("I", "Z"),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == RED_DOT_FIELD
        } == true
    },
)

// The layout's binding step (the only (MainLayoutBottomNavigationBinding)V on the layout class that
// reads the Messages red dot): it collects the red dots into a list once.
internal object MessagesRedDotBindFingerprint : Fingerprint(
    definingClass = NAV_LAYOUT,
    returnType = "V",
    parameters = listOf(NAV_BINDING),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == RED_DOT_FIELD
        } == true
    },
)

@Suppress("unused")
val hideMessagesTabPatch = bytecodePatch(
    name = "Hide Messages tab",
    description = "Hides the Messages tab from the bottom bar.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WEMUSLIM)

    dependsOn(bypassNativeEncryptionPatch)

    extendWith("extensions/wemuslim.mpe")

    execute {
        // 1. The bottom bar: every tab model starts visible (a boolean the constructor sets to true).
        // MainBottomNavigationItemView sets its visibility from that flag, the layout counts only
        // visible tabs to number the pages (a hidden one gets -1) and its select/click methods return
        // early for a hidden tab, so a deep link to "message" selects nothing. The model constructor
        // therefore stores false for type 3. Ummah is hidden the same way by hasUmmah, and the
        // layout only rewrites the Ummah flag, never this one.
        val typeMethod = NavigationTabTypeFingerprint.method
        val tabType = typeMethod.definingClass
        val typeField = (typeMethod.instructions.firstOrNull() as? ReferenceInstruction)?.reference as? FieldReference
            ?: throw PatchException("Hide Messages tab: $tabType type method does not start with a field read")
        val constructor = mutableClassDefBy(tabType).methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("I") &&
                it.implementation != null
        } ?: throw PatchException("Hide Messages tab: no $tabType-><init>(I)V")
        val visibleField = constructor.instructions
            .filter { it.opcode == Opcode.IPUT_BOOLEAN }
            .map { (it as ReferenceInstruction).reference as FieldReference }
            .singleOrNull { it.definingClass == tabType }
            ?: throw PatchException("Hide Messages tab: $tabType-><init>(I)V has no single boolean field store")
        val lastReturn = constructor.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        if (lastReturn < 0) throw PatchException("Hide Messages tab: $tabType-><init>(I)V has no return-void")
        // p1 (the type) is dead at the return; type - 3 == 0 leaves p1 == 0, the false to store.
        constructor.addInstructionsWithLabelsChecked(
            lastReturn,
            """
                iget p1, p0, $tabType->${typeField.name}:${typeField.type}
                add-int/lit8 p1, p1, -0x3
                if-nez p1, :keep
                iput-boolean p1, p0, $tabType->${visibleField.name}:Z
                :keep
                nop
            """.trimIndent(),
        )

        // 2. The pager: MainActivity adds the message page unconditionally, and the tab bar numbers
        // its tabs by visible entries, so the page has to go too or the Me tab would point one past
        // the list. Pages are looked up by their type string, never by a fixed index. The call to
        // remove is the ArrayList.add after the "messageTab" null check.
        val builder = PagerBuilderFingerprint.method
        val instructions = builder.instructions.toList()
        val nameIndex = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == MESSAGE_TAB_PAGE
        }
        val addIndex = (nameIndex + 1 until instructions.size).firstOrNull {
            val call = (instructions[it] as? ReferenceInstruction)?.reference as? MethodReference
            instructions[it].opcode == Opcode.INVOKE_VIRTUAL && call?.definingClass == "Ljava/util/ArrayList;" &&
                call.name == "add" && call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        } ?: throw PatchException("Hide Messages tab: no ArrayList.add after \"$MESSAGE_TAB_PAGE\" in ${builder.definingClass}")
        // The result of add() is not read (no move-result follows).
        if (instructions.getOrNull(addIndex + 1)?.opcode == Opcode.MOVE_RESULT) {
            throw PatchException("Hide Messages tab: the add() result of \"$MESSAGE_TAB_PAGE\" is read")
        }
        builder.removeInstruction(addIndex)

        // 3. The unread badge (msgMainTabRedDotView) sits between the Messages and Me items and its
        // setter never looks at the tab's visibility, so keep it GONE: the count setter hides it and
        // returns, and the layout's binding step hides it once at the start.
        val setter = MessagesRedDotSetterFingerprint.method
        val dotField = setter.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .firstOrNull { it.name == RED_DOT_FIELD }
            ?: throw PatchException("Hide Messages tab: $RED_DOT_FIELD not read by the unread setter")
        val bindingField = (setter.instructions.firstOrNull() as? ReferenceInstruction)?.reference as? FieldReference
        if (bindingField == null || bindingField.type != NAV_BINDING) {
            throw PatchException("Hide Messages tab: the unread setter does not start by reading the binding")
        }
        setter.addInstructionsWithLabelsChecked(
            0,
            """
                iget-object p1, p0, ${bindingField.definingClass}->${bindingField.name}:${bindingField.type}
                if-eqz p1, :done
                iget-object p1, p1, ${dotField.definingClass}->${dotField.name}:${dotField.type}
                const/16 p2, 0x8
                invoke-virtual {p1, p2}, Landroid/view/View;->setVisibility(I)V
                :done
                return-void
            """.trimIndent(),
        )

        // The binding step reuses p1 for the red-dot list at its end, so the dot is hidden right after
        // it is read from the binding (p1 holds it there).
        val bind = MessagesRedDotBindFingerprint.method
        val bindRead = bind.instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == RED_DOT_FIELD
        }
        if (bindRead < 0 || bind.instructions[bindRead].opcode != Opcode.IGET_OBJECT) {
            throw PatchException("Hide Messages tab: ${bind.name} does not read $RED_DOT_FIELD with iget-object")
        }
        val dotRegister = (bind.instructions[bindRead] as OneRegisterInstruction).registerA
        val bindParameter = bind.implementation!!.registerCount - 1 // (binding) is the last parameter: p1
        if (dotRegister != bindParameter) {
            throw PatchException("Hide Messages tab: the red dot is read into v$dotRegister, not p1 (v$bindParameter)")
        }
        bind.addInstructionsChecked(
            bindRead + 1,
            "invoke-static {p1}, $VIEW_HIDER->gone(Landroid/view/View;)V",
        )
    }
}
