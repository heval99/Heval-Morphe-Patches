package app.template.patches.sofascore.shared

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.addInstructionsChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Sofascore queues its one-time modals (the rating dialog, Match of the Week, Active
 * subscription, ...) through BaseActivity. Each modal is an R8-renamed object whose <clinit>
 * passes a display name ("Rating Info", "Active subscription") and an eligibility predicate, a
 * Function1 over the app's SharedPreferences, to the queue's descriptor constructor. The queue
 * shows a modal only while its predicate returns true, and the dialog's dismiss completes the
 * queue item later. A predicate forced to false drops the modal before it is queued, so no queue
 * item waits for a dismiss that never comes (skipping the dialog itself would stall later modals).
 *
 * The predicates are cases of one shared R8 lambda class, so the predicate is found through the
 * Function1 the descriptor's <clinit> creates, and then by a string of its own case.
 */

private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"

private fun Method.holdsString(value: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true

private fun Method.isFunction1Invoke() = name == "invoke" && returnType == "Ljava/lang/Object;" &&
    parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")

/**
 * The invoke(Object)Object of the predicate lambda that the modal named [modalName] is created
 * with, identified by [anchor], a string of the predicate's own switch case.
 */
internal fun BytecodePatchContext.modalPredicate(modalName: String, anchor: String): MutableMethod {
    val descriptors = classDefByStrings(modalName).filter { classDef ->
        classDef.methods.any { it.name == "<clinit>" && it.holdsString(modalName) }
    }
    val descriptor = descriptors.singleOrNull()
        ?: throw PatchException("Expected one modal named \"$modalName\", found ${descriptors.map { it.type }}")

    val created = descriptor.methods.single { it.name == "<clinit>" }.implementation!!.instructions
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .toSet()
    val predicates = created.mapNotNull(::classDefByOrNull)
        .filter { FUNCTION1 in it.interfaces }
        .filter { classDef -> classDef.methods.any { it.isFunction1Invoke() && it.holdsString(anchor) } }
    val predicate = predicates.singleOrNull() ?: throw PatchException(
        "The \"$modalName\" modal (${descriptor.type}) creates no single Function1 holding \"$anchor\": " +
            "${predicates.map { it.type }}"
    )
    return mutableClassDefBy(predicate).methods.single { it.isFunction1Invoke() }
}

/**
 * Overwrites the boolean result of a call that [isCall] selects: right after its move-result the
 * register is set to [value]. Without [afterString] the call must be unique in the method. With
 * it, the string must be loaded once and the first call after it must be the selected one (the
 * string is its argument).
 */
internal fun MutableMethod.forceCallResult(
    description: String,
    value: Boolean,
    afterString: String? = null,
    isCall: (MethodReference) -> Boolean,
) {
    // Only invoke-* instructions carry a MethodReference.
    fun callAt(index: Int) = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
    val callIndex = if (afterString == null) {
        val calls = instructions.indices.filter { i -> callAt(i)?.let(isCall) == true }
        calls.singleOrNull()
            ?: throw PatchException("Expected one $description call in $definingClass->$name, found ${calls.size}")
    } else {
        val loads = instructions.indices.filter { i ->
            ((instructions[i] as? ReferenceInstruction)?.reference as? StringReference)?.string == afterString
        }
        val load = loads.singleOrNull()
            ?: throw PatchException("Expected \"$afterString\" once in $definingClass->$name, found ${loads.size}")
        val next = (load + 1 until instructions.size).firstOrNull { callAt(it) != null }
        next?.takeIf { callAt(it)?.let(isCall) == true }
            ?: throw PatchException("The first call after \"$afterString\" in $definingClass->$name is not $description")
    }
    val moveResult = instructions.getOrNull(callIndex + 1)
    if (moveResult?.opcode != Opcode.MOVE_RESULT) {
        throw PatchException("$description in $definingClass->$name is not followed by move-result")
    }
    // An insert keeps branch labels on the instruction it pushes down, so a jump straight to the
    // instruction after the move-result would bypass the forced value.
    if (instructions.getOrNull(callIndex + 2)?.location?.labels?.isNotEmpty() != false) {
        throw PatchException("$description in $definingClass->$name: the next instruction is a branch target")
    }
    val register = (moveResult as OneRegisterInstruction).registerA
    val literal = if (value) "0x1" else "0x0"
    // const/4 only addresses v0-v15 (AGENTS.md rule 11).
    addInstructionsChecked(
        callIndex + 2,
        if (register < 16) "const/4 v$register, $literal" else "const/16 v$register, $literal",
    )
}
