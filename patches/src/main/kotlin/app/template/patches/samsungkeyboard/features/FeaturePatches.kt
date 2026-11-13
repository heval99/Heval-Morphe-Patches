package app.template.patches.samsungkeyboard.features

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_SAMSUNG_KEYBOARD
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private val log = java.util.logging.Logger.getLogger("SamsungKeyboard")

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.register() = (this as OneRegisterInstruction).registerA

/** Whether the instruction names register [r] (or, for a wide operation, the register before it). Invoke arguments that are wide are caught only by name; stock has none here. */
private fun Instruction.touches(r: Int): Boolean {
    val used = buildList {
        if (this@touches is OneRegisterInstruction) add(registerA)
        if (this@touches is com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction) add(registerB)
        if (this@touches is com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction) add(registerC)
        if (this@touches is FiveRegisterInstruction) {
            val n = registerCount
            if (n > 0) add(registerC)
            if (n > 1) add(registerD)
            if (n > 2) add(registerE)
            if (n > 3) add(registerF)
            if (n > 4) add(registerG)
        }
        if (this@touches is RegisterRangeInstruction) addAll(startRegister until startRegister + registerCount)
    }
    val wide = opcode.name.contains("WIDE") || opcode.name.endsWith("_LONG") || opcode.name.endsWith("_DOUBLE")
    return used.any { it == r || (wide && it == r - 1) }
}

private fun MutableMethod.sig() = "$definingClass->$name"

private fun MutableMethod.code() = implementation?.instructions?.toList()
    ?: throw PatchException("${sig()} has no implementation")

private fun FieldReference.key() = "$definingClass->$name:$type"

/**
 * Forces the boolean stored by the `sput-boolean` at [sputIndex] to true: `const/4 vX, 0x1` goes in front of it, in the
 * store's own register. Branches to the store land on the new constant, so every path stores true.
 */
private fun MutableMethod.forceTrueBeforeSput(sputIndex: Int, what: String) {
    val sput = code()[sputIndex]
    if (sput.opcode != Opcode.SPUT_BOOLEAN) throw PatchException("$what: instruction $sputIndex of ${sig()} is not a boolean store")
    val register = sput.register()
    if (register > 15) throw PatchException("$what: the store uses v$register, which const/4 cannot hold")
    addInstructionsAtControlFlowLabelChecked(sputIndex, "const/4 v$register, 0x1")
}

/** The first boolean store within [window] instructions after the floating-feature key [key] in the features initializer. */
private fun MutableMethod.flagStoreAfter(key: String, window: Int, what: String): Int {
    val code = code()
    val keys = code.indices.filter { code[it].string() == key }
    if (keys.size != 1) throw PatchException("$what: expected one \"$key\" in ${sig()}, found ${keys.size}")
    val start = keys.single()
    return (start + 1..minOf(start + window, code.size - 1)).firstOrNull { code[it].opcode == Opcode.SPUT_BOOLEAN }
        ?: throw PatchException("$what: no boolean store within $window instructions after \"$key\" in ${sig()}")
}

private fun BytecodePatchContext.forceFloatingFlag(key: String, window: Int, what: String) {
    val clinit = FeaturesClinitFingerprint.method
    val index = clinit.flagStoreAfter(key, window, what)
    val field = clinit.code()[index].field()!!
    clinit.forceTrueBeforeSput(index, what)
    log.info("$what: ${field.key()} is true on every phone")
}

@Suppress("unused")
val biggerClipboardHistoryPatch = bytecodePatch(
    name = "Bigger clipboard history",
    description = "Keeps more items in the clipboard history.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    val size = intOption(
        key = "clipboardHistorySize",
        default = 200,
        values = mapOf("100" to 100, "200" to 200, "500" to 500, "1000" to 1000),
        title = "Clipboard history size",
        description = "How many items the clipboard history keeps.",
        required = true,
    ) { it != null && it >= 1 }

    execute {
        val what = "Bigger clipboard history"
        val limit = size.value ?: throw PatchException("$what: no size")
        if (limit < 1) throw PatchException("$what: the size must be at least 1")

        val enforcer = ClipEnforcerFingerprint.method
        val code = enforcer.code()

        // 1. The cap is the first plain int static the enforcer reads from another class; set it where that class's
        //    initializer stores it (R8 gave stock one store, reached from a 200 and a 40 branch).
        val capField = code.firstNotNullOfOrNull { ins ->
            ins.field()?.takeIf { ins.opcode == Opcode.SGET && it.type == "I" && it.definingClass != enforcer.definingClass }
        } ?: throw PatchException("$what: no int static read from another class in ${enforcer.sig()}")
        val holder = mutableClassDefBy(capField.definingClass)
        val clinit = holder.methods.singleOrNull { it.name == "<clinit>" }
            ?: throw PatchException("$what: ${capField.definingClass} has no single <clinit>")
        val stores = clinit.code().withIndex().filter { (_, ins) ->
            ins.opcode == Opcode.SPUT && ins.field()?.key() == capField.key()
        }
        if (stores.size != 1) throw PatchException("$what: expected one store to ${capField.key()}, found ${stores.size}")
        val store = stores.single()
        clinit.addInstructionsAtControlFlowLabelChecked(store.index, "const v${store.value.register()}, $limit")

        // 2. With a finite cap, if every clip is pinned the unpinned list is empty and ArrayList.get(0) throws: leave
        //    the enforcer when the list is empty.
        val getIndex = code.indices.single { index ->
            val ref = (code[index] as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference
            ref != null && ref.definingClass == "Ljava/util/ArrayList;" && ref.name == "get" && ref.parameterTypes.map(CharSequence::toString) == listOf("I")
        }
        val get = code[getIndex]
        val index = (get as? FiveRegisterInstruction)?.registerD
        val list = (get as? FiveRegisterInstruction)?.registerC
            ?: (get as? RegisterRangeInstruction)?.startRegister
            ?: throw PatchException("$what: ArrayList.get in ${enforcer.sig()} is not an invoke")
        // A scratch register for the answer: a local that nothing after the call touches (stock: v1, last used by the
        // loop before it). Locals are the registers below the parameters.
        val locals = enforcer.implementation!!.registerCount - enforcer.parameterTypes.size -
            (if (com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(enforcer.accessFlags)) 0 else 1)
        val result = (0 until minOf(locals, 16)).firstOrNull { r ->
            r != list && r != index && code.drop(getIndex).none { it.touches(r) }
        } ?: throw PatchException("$what: no free register in ${enforcer.sig()} ($locals locals)")
        val done = code.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        if (done < 0) throw PatchException("$what: ${enforcer.sig()} has no return-void")
        enforcer.addInstructionsAtControlFlowLabelChecked(
            getIndex,
            """
                invoke-virtual/range {v$list .. v$list}, Ljava/util/ArrayList;->isEmpty()Z
                move-result v$result
                if-nez v$result, :morphe_history_done
            """,
            ExternalLabel("morphe_history_done", enforcer.getInstruction(done)),
        )
        log.info("$what: ${capField.key()} is $limit, empty list guarded in ${enforcer.sig()}")
    }
}

@Suppress("unused")
val clipboardColumnsPatch = bytecodePatch(
    name = "Clipboard columns",
    description = "Sets how many columns the clipboard panel has, and how many recent items its top row shows.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    val columns = intOption(
        key = "clipboardColumns",
        default = 3,
        values = mapOf("1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6),
        title = "Columns",
        description = "How many columns the clipboard panel has (1 to 6).",
        required = true,
    ) { it != null && it in 1..6 }

    execute {
        val count = columns.value ?: throw PatchException("Clipboard columns: no value")
        if (count !in 1..6) throw PatchException("Clipboard columns: $count is not between 1 and 6")
        ClipboardColCountFingerprint.method.returnEarly(count)
    }
}

@Suppress("unused")
val swipeCursorControlPatch = bytecodePatch(
    name = "Swipe cursor control",
    description = "Offers moving the cursor by swiping on the space bar, for non-Samsung phones.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        forceFloatingFlag(FLOATING_CURSOR_KEY, window = 8, what = "Swipe cursor control")
    }
}

@Suppress("unused")
val oneHandedKeyboardPatch = bytecodePatch(
    name = "One-handed keyboard",
    description = "Offers the one-handed keyboard, for non-Samsung phones.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        forceFloatingFlag(FLOATING_ONEHAND_KEY, window = 16, what = "One-handed keyboard")
    }
}

@Suppress("unused")
val toolbarItemCountPatch = bytecodePatch(
    name = "Toolbar item count",
    description = "Lets you place more items in the toolbar's top row; rearrange them in the toolbar editor.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    val items = intOption(
        key = "toolbarItems",
        default = 8,
        values = mapOf("8" to 8, "10" to 10, "12" to 12),
        title = "Items",
        description = "How many items the toolbar's top row holds (8 to 12).",
        required = true,
    ) { it != null && it in 8..12 }

    execute {
        val what = "Toolbar item count"
        val count = items.value ?: throw PatchException("$what: no value")
        if (count !in 8..12) throw PatchException("$what: $count is not between 8 and 12")

        // 1. The cap method, cross-checked against the saved-toolbar loader: the loader holds the default-set object
        //    (a field typed as the cap's class) and asks it for the cap through an interface call of the same name.
        val cap = ToolbarCapFingerprint.method
        val loader = ToolbarUserSetLoaderFingerprint.method
        val loaderCode = loader.code()
        val setFields = loaderCode.mapNotNull { ins ->
            ins.field()?.takeIf { ins.opcode == Opcode.IGET_OBJECT && it.definingClass == loader.definingClass && it.type == cap.definingClass }
        }.map { it.key() }.distinct()
        if (setFields.size != 1) {
            throw PatchException("$what: ${loader.sig()} reads ${setFields.size} fields typed ${cap.definingClass}, expected one (cap ${cap.sig()})")
        }
        val capCalls = loaderCode.count { ins ->
            val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
            ins.opcode == Opcode.INVOKE_INTERFACE && ref != null && ref.name == cap.name && ref.parameterTypes.isEmpty() && ref.returnType == "I"
        }
        if (capCalls == 0) throw PatchException("$what: ${loader.sig()} never asks an interface for ${cap.name}()I, so ${cap.sig()} is not the toolbar cap")

        // 2. Every 7 or 8 loaded into the answer register becomes the chosen count; the 4 of the small modes stays.
        val code = cap.code()
        val edits = code.withIndex().filter { (_, ins) ->
            (ins.opcode == Opcode.CONST_4 || ins.opcode == Opcode.CONST_16 || ins.opcode == Opcode.CONST) &&
                (ins as NarrowLiteralInstruction).narrowLiteral.let { it == 7 || it == 8 }
        }
        if (edits.size < 5) throw PatchException("$what: only ${edits.size} cap constants found in ${cap.sig()}, expected at least 5")
        if (code.none { it is NarrowLiteralInstruction && it.narrowLiteral == 4 }) {
            throw PatchException("$what: the small-mode constant 4 is gone from ${cap.sig()}")
        }
        for ((index, ins) in edits) {
            val register = ins.register()
            if (register > 255) throw PatchException("$what: v$register in ${cap.sig()} does not fit const/16")
            cap.replaceInstruction(index, "const/16 v$register, 0x${count.toString(16)}")
        }
        log.info("$what: ${cap.sig()} answers $count (${edits.size} constants)")
    }
}

@Suppress("unused")
val autofillInAnyFieldPatch = bytecodePatch(
    name = "Autofill in any field",
    description = "Shows password and autofill suggestions even when the field already has text.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        val what = "Autofill in any field"
        val method = InlineSuggestionsResponseFingerprint.method
        val code = method.code()

        // The skip: text before the cursor has a length, and a positive length jumps to the "skipping" log. Make the
        // length read zero, right after its move-result, in the register the branch tests.
        val length = code.indexOfFirst { ins ->
            val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
            ins.opcode == Opcode.INVOKE_INTERFACE && ref?.definingClass == "Ljava/lang/CharSequence;" && ref.name == "length" && ref.parameterTypes.isEmpty()
        }
        if (length < 0) throw PatchException("$what: no CharSequence.length() in ${method.sig()}")
        val result = code[length + 1]
        if (result.opcode != Opcode.MOVE_RESULT) throw PatchException("$what: length() in ${method.sig()} is not followed by move-result")
        val register = result.register()
        val branch = (length + 2 until code.size).firstOrNull { code[it].opcode == Opcode.IF_LEZ && code[it].register() == register }
            ?: throw PatchException("$what: no if-lez on v$register after length() in ${method.sig()}")
        if ((branch + 1..minOf(branch + 3, code.size - 1)).none { code[it].string() == SKIP_SUGGESTIONS_LOG }) {
            throw PatchException("$what: the skip log does not follow the length branch in ${method.sig()}")
        }
        if (register > 15) throw PatchException("$what: length() lands in v$register, which const/4 cannot hold")
        method.addInstructionsAtControlFlowLabelChecked(length + 2, "const/4 v$register, 0x0")
        log.info("$what: the text length reads 0 in ${method.sig()}")
    }
}

@Suppress("unused")
val restoreKeyVibrationPatch = bytecodePatch(
    name = "Restore key vibration",
    description = "Brings back the vibration on key taps, for non-Samsung phones.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        val what = "Restore key vibration"
        val gate = KeyVibrationGateFingerprint.method
        val flag = gate.code().firstNotNullOfOrNull { ins -> ins.field()?.takeIf { ins.opcode == Opcode.SGET_BOOLEAN } }
            ?: throw PatchException("$what: no boolean flag read in ${gate.sig()}")
        val clinit = FeaturesClinitFingerprint.method
        if (flag.definingClass != clinit.definingClass) {
            throw PatchException("$what: the flag ${flag.key()} is not set by the features initializer ${clinit.definingClass}")
        }
        val stores = clinit.code().withIndex().filter { (_, ins) ->
            ins.opcode == Opcode.SPUT_BOOLEAN && ins.field()?.key() == flag.key()
        }
        if (stores.size != 1) throw PatchException("$what: expected one store to ${flag.key()}, found ${stores.size}")
        clinit.forceTrueBeforeSput(stores.single().index, what)
        log.info("$what: ${flag.key()} is true on every phone")
    }
}
