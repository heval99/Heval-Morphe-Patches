package app.template.patches.jyllandsposten.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.template.patches.shared.Constants.COMPATIBILITY_JYLLANDSPOSTEN
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val WEBVIEW = "Landroid/webkit/WebView;"

/**
 * Jyllands-Posten renders articles in a WebView (the app's own wrapper creates it, enables
 * JavaScript and adds the bridge, and never sets a WebViewClient). The page's ad slots are the
 * website's own requests, so the SDK patches do not apply; this installs the shared blocking client
 * from the webviewads extension on the wrapper's WebView. Content, consent and the paywall pages
 * are untouched; only the ad/tracking hosts are answered with an empty response.
 */
@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Blocks the website's ad requests inside the article WebView.",
) {
    compatibleWith(COMPATIBILITY_JYLLANDSPOSTEN)

    extendWith("extensions/webviewads.mpe")

    execute {
        val candidates = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lcom/") || classDef.type.startsWith("Landroid") ||
                classDef.type.startsWith("Lkotlin")
            ) {
                return@classDefForEach
            }
            classDef.methods.forEach { method ->
                val insns = method.implementation?.instructions?.toList() ?: return@forEach
                val createsWebView = insns.any {
                    it.opcode == Opcode.NEW_INSTANCE && it.typeOf() == WEBVIEW
                }
                val addsBridge = insns.any { insn ->
                    val call = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == WEBVIEW && call.name == "addJavascriptInterface"
                }
                if (createsWebView && addsBridge) candidates += classDef to method
            }
        }
        val (classDef, method) = candidates.singleOrNull() ?: throw PatchException(
            "expected one app WebView wrapper, found ${candidates.size}: " +
                candidates.joinToString { "${it.first.type}->${it.second.name}" },
        )
        val field = classDef.fields.singleOrNull { it.type == WEBVIEW }
            ?: throw PatchException("${classDef.type} has no $WEBVIEW field")
        val mutable = mutableClassDefBy(classDef).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }
        val returns = mutable.instructions.count { it.opcode == Opcode.RETURN_VOID }
        if (returns != 1) {
            throw PatchException("${classDef.type}->${method.name} has $returns returns, expected one")
        }
        val returnIndex = mutable.instructions.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        mutable.addInstructionsAtControlFlowLabelChecked(
            returnIndex,
            """
                move-object/from16 v0, p0
                iget-object v1, v0, ${field.smali()}
                invoke-static {v1}, Lapp/template/extension/webviewads/AdBlock;->install(Landroid/webkit/WebView;)V
            """,
        )
    }
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.typeOf(): String? =
    ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

private fun FieldReference.smali() = "$definingClass->$name:$type"
