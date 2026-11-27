package app.template.patches.brave.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import app.template.patches.shared.addInstructionsChecked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val HOOKS = "Lapp/template/extension/brave/ShareHooks;"
private const val CLIP_DATA = "Landroid/content/ClipData;"
private const val INTENT = "Landroid/content/Intent;"
// R8 may narrow the Context parameter to an obfuscated type, so it is matched as any object ("L").
private const val CONTEXT = "L"

// Chromium's Clipboard wrapper (d14 in 1.100.58): the one (ClipData)V that calls
// ClipboardManager.setPrimaryClip. "Copy link", the address bar and text copies all end here.
internal object ClipboardSinkFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(CLIP_DATA),
    custom = { method, _ ->
        method.implementation?.instructions?.toList().orEmpty().any {
            val ref = (it as? ReferenceInstruction)?.reference
            ref is MethodReference && ref.definingClass == "Landroid/content/ClipboardManager;" &&
                ref.name == "setPrimaryClip"
        }
    },
)

// ShareHelper's intent builder (lhh.a(ShareParams) in 1.100.58): the static method that returns the
// Intent of the Chromium share sheet; "org.chromium.chrome.browser.share_origin" is a kept extra name.
internal object ShareIntentBuilderFingerprint : Fingerprint(
    returnType = INTENT,
    filters = listOf(string("org.chromium.chrome.browser.share_origin")),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

// The static (Context, String)V helpers that send plain text through ACTION_SEND (two in 1.100.58).
internal object PlainTextShareFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(CONTEXT, "Ljava/lang/String;"),
    filters = listOf(
        string("android.intent.action.SEND"),
        string("text/plain"),
        string("android.intent.extra.TEXT"),
    ),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

@Suppress("unused")
val stripTrackingParamsPatch = bytecodePatch(
    name = "Strip tracking parameters",
    description = "Removes tracking parameters from links you copy or share.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    extendWith("extensions/brave.mpe")

    execute {
        // ── 1. Clipboard: every copy goes through the (ClipData)V sink ─────────────────────
        //  The extension rebuilds the clip (same description) only when the text changed.
        ClipboardSinkFingerprint.method.addInstructionsChecked(
            0,
            """
                invoke-static/range {p1 .. p1}, $HOOKS->sanitizeClip($CLIP_DATA)$CLIP_DATA
                move-result-object p1
            """,
        )

        // ── 2. Share sheet: clean EXTRA_TEXT of the finished Intent before every return ────
        val builder = ShareIntentBuilderFingerprint.method
        val returns = builder.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_OBJECT }
            .map { it.index to (it.value as OneRegisterInstruction).registerA }
        if (returns.isEmpty()) throw PatchException("${builder.name}: no return-object in the share Intent builder")
        returns.asReversed().forEach { (index, register) ->
            builder.addInstructionsAtControlFlowLabelChecked(
                index,
                "invoke-static {v$register}, $HOOKS->sanitizeIntent($INTENT)V",
            )
        }

        // ── 3. Plain text shares: clean the text argument on entry (every match) ────────────
        val senders = PlainTextShareFingerprint.matchAllOrNull().orEmpty()
        if (senders.isEmpty()) throw PatchException("no plain text ACTION_SEND helper found")
        senders.forEach { match ->
            match.method.addInstructionsChecked(
                0,
                """
                    invoke-static/range {p1 .. p1}, $HOOKS->sanitizeText(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
            )
        }
    }
}
