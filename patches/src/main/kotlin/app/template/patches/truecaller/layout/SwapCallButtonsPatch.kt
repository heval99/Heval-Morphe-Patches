package app.template.patches.truecaller.layout

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECALLER
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.requireNoBranchTargetAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Element
import org.w3c.dom.Node

// Truecaller's own incoming call notification layouts: the collapsed row (icon buttons, also part of
// the Android 12 expanded layout) and the older expanded layout (text buttons). Each has the decline
// button right before the answer button in one LinearLayout.
private val NOTIFICATION_BUTTON_LAYOUTS = listOf(
    "res/layout/notification_call_incoming.xml",
    "res/layout/notification_call_incoming_big.xml",
)

/** The single element of [path] whose android:id is [id], failing loudly when it is missing or duplicated. */
private fun org.w3c.dom.Document.viewById(path: String, id: String): Element {
    val all = getElementsByTagName("*")
    val matches = (0 until all.length)
        .mapNotNull { all.item(it) as? Element }
        .filter { it.getAttribute("android:id").substringAfter('/') == id }
    return matches.singleOrNull()
        ?: throw PatchException("$path: expected one view \"$id\", found ${matches.size}")
}

private fun Element.previousElement(): Element? {
    var node: Node? = previousSibling
    while (node != null && node !is Element) node = node.previousSibling
    return node as? Element
}

// The answer button moves in front of the decline button. Each keeps its id (the PendingIntents are
// bound by id), text, icon and colour; the margins stay with the slot, so the spacing is unchanged.
private val swapCallNotificationButtonsResourcePatch = resourcePatch(
    description = "Swaps the answer and decline buttons in Truecaller's own incoming call notification.",
) {
    compatibleWith(COMPATIBILITY_TRUECALLER)

    execute {
        decode(*NOTIFICATION_BUTTON_LAYOUTS.toTypedArray())
        for (path in NOTIFICATION_BUTTON_LAYOUTS) {
            document(path).use { doc ->
                val decline = doc.viewById(path, "button_decline")
                val answer = doc.viewById(path, "button_answer")
                if (answer.previousElement() !== decline) {
                    throw PatchException("$path: the decline button is not right before the answer button")
                }
                val margins = { view: Element ->
                    (0 until view.attributes.length).map { view.attributes.item(it) }
                        .filter { it.nodeName.startsWith("android:layout_margin") }
                        .associate { it.nodeName to it.nodeValue }
                }
                val declineMargins = margins(decline)
                val answerMargins = margins(answer)
                declineMargins.keys.forEach(decline::removeAttribute)
                answerMargins.keys.forEach(answer::removeAttribute)
                answerMargins.forEach(decline::setAttribute)
                declineMargins.forEach(answer::setAttribute)
                decline.parentNode.insertBefore(answer, decline)
            }
        }
    }
}

@Suppress("unused")
val swapCallButtonsPatch = bytecodePatch(
    name = "Swap call answer and decline buttons",
    description = "Puts the answer button on the left and the decline button on the right for incoming calls.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_TRUECALLER)

    dependsOn(swapCallNotificationButtonsResourcePatch)

    execute {
        // 1. The full screen incoming call UI. Each swipe button's modifier gets offset(x, y) right
        // before its test tag is loaded: the decline button -width / 3.5, the answer button
        // +width / 3.5. Negating x on both moves each to the other side. Offsets follow the layout
        // direction, so right-to-left layouts are mirrored the same way. The offset call is found by
        // shape: a static (Float, Float, Int, Modifier)Modifier call, Modifier being this
        // composable's first parameter type.
        val method = IncomingCallButtonsFingerprint.method
        val modifier = method.parameterTypes.firstOrNull()?.toString()
            ?: throw PatchException("The incoming call buttons composable has no parameters")
        val offsetParameters = listOf("F", "F", "I", modifier)

        // Highest index first, so the earlier index stays valid after the insert.
        for (tag in listOf("answerCallButton", "rejectCallButton")) {
            val instructions = method.instructions.toList()
            val tagIndex = instructions.indexOfFirst {
                it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == tag
            }
            val offsetIndex = (tagIndex - 1 downTo 0).firstOrNull { index ->
                val instruction = instructions[index]
                val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                instruction.opcode == Opcode.INVOKE_STATIC && call?.returnType == modifier &&
                    call.parameterTypes.map(CharSequence::toString) == offsetParameters
            } ?: throw PatchException("No offset modifier before the \"$tag\" swipe button")
            // offset's first argument is x; neg-float takes 4-bit registers (checked).
            val x = method.getInstruction<FiveRegisterInstruction>(offsetIndex).registerC
            method.requireNoBranchTargetAt(offsetIndex, "Swap call answer and decline buttons")
            method.addInstructionsChecked(offsetIndex, "neg-float v$x, v$x")
        }

        // 2. The incoming call notification. The system call style notification (Android 12+,
        // when the featureCallStyleNotifications experiment is on) always shows decline first, so
        // the builder's call style check is forced false: Truecaller's own layout is used, and the
        // resource part above swaps its buttons. Only the incoming call notification changes; the
        // ongoing call notification keeps its style.
        IncomingCallNotificationFingerprint.let { fingerprint ->
            val notification = fingerprint.method
            val check = fingerprint.instructionMatches.last().index
            val instructions = notification.instructions.toList()
            val moveResult = instructions.getOrNull(check + 1)
            if (moveResult?.opcode != Opcode.MOVE_RESULT) {
                throw PatchException("The incoming call notification's call style check result is not kept")
            }
            val result = (moveResult as OneRegisterInstruction).registerA

            // The first branch on the result picks the layout; the result must not change before it.
            val branch = (check + 2 until instructions.size).firstOrNull { index ->
                val instruction = instructions[index]
                if (instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA.let {
                        it == result || (instruction.opcode.setsWideRegister() && it == result - 1)
                    }
                ) {
                    throw PatchException("The call style check result is overwritten at $index before it is used")
                }
                (instruction.opcode == Opcode.IF_EQZ || instruction.opcode == Opcode.IF_NEZ) &&
                    (instruction as OneRegisterInstruction).registerA == result
            } ?: throw PatchException("The call style check result is never branched on")
            val ownLayoutPath = if (instructions[branch].opcode == Opcode.IF_EQZ) {
                (instructions[branch] as BuilderOffsetInstruction).target.location.index
            } else {
                branch + 1
            }
            // The false path must build the notification class that inflates RemoteViews.
            val ownLayout = (ownLayoutPath until instructions.size).firstOrNull { instructions[it].opcode == Opcode.NEW_INSTANCE }
                ?.let { ((instructions[it] as ReferenceInstruction).reference as TypeReference).type }
                ?: throw PatchException("The call style check's false path builds no notification")
            val inflatesRemoteViews = mutableClassDefBy(ownLayout).methods.any { constructor ->
                constructor.name == "<init>" && constructor.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                        call.definingClass == "Landroid/widget/RemoteViews;" && call.name == "<init>"
                    } == true
                } == true
            }
            if (!inflatesRemoteViews) {
                throw PatchException("$ownLayout, built when the call style check is false, inflates no RemoteViews")
            }

            notification.requireNoBranchTargetAt(check + 2, "Swap call answer and decline buttons")
            val forceFalse = if (result < 16) "const/4 v$result, 0x0" else "const/16 v$result, 0x0"
            notification.addInstructionsChecked(check + 2, forceFalse)
        }
    }
}
