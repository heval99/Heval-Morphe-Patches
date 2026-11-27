package app.template.patches.transfermarkt.shared

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.template.patches.shared.addInstructionsWithLabelsChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/template/extension/transfermarkt/WebViewBlocklistPatch;"

internal const val SYSTEM_WEBVIEW_ENGINE = "Lorg/apache/cordova/engine/SystemWebViewEngine;"
internal const val IONIC_WEBVIEW_ENGINE = "Lcom/ionicframework/cordova/webview/IonicWebViewEngine;"
internal const val WEB_RESOURCE_RESPONSE = "Landroid/webkit/WebResourceResponse;"
internal val INTERCEPT_PARAMETERS = listOf("Landroid/webkit/WebView;", "Landroid/webkit/WebResourceRequest;")

// Both Transfermarkt patches use the extension; it is merged once through this dependency.
internal val webViewBlocklistExtensionPatch = bytecodePatch {
    extendWith("extensions/transfermarkt.mpe")
}

/**
 * Makes every concrete `shouldInterceptRequest(WebView, WebResourceRequest)` of Cordova's
 * SystemWebViewClient and its subclasses ask [EXTENSION_CLASS_DESCRIPTOR]`->`[hook] first and
 * return its response when it is not null. The live client is the Ionic WebView's
 * IonicWebViewEngine$ServerClient (it never calls super), so the subclasses matter. The
 * InAppBrowser's client extends WebViewClient directly and is not touched: it shows external
 * pages, not the app.
 *
 * Fails when SystemWebViewClient is missing or nothing was hooked. Returns the hooked methods.
 */
internal fun BytecodePatchContext.interceptWebViewRequests(hook: String, patchName: String): List<MutableMethod> {
    val superclasses = mutableMapOf<String, String?>()
    val ionicClients = mutableSetOf<String>()
    // Since 4.4.0 R8 renames SystemWebViewClient (and the Ionic ServerClient), so the base client
    // is found by shape: a direct WebViewClient subclass that holds the kept SystemWebViewEngine
    // in a field, is built from it and overrides shouldInterceptRequest(WebView, WebResourceRequest).
    val bases = mutableListOf<String>()
    classDefForEach { classDef ->
        superclasses[classDef.type] = classDef.superclass
        if (classDef.instanceFields.any { it.type == IONIC_WEBVIEW_ENGINE }) ionicClients += classDef.type
        if (classDef.superclass == "Landroid/webkit/WebViewClient;" &&
            classDef.instanceFields.any { it.type == SYSTEM_WEBVIEW_ENGINE } &&
            classDef.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(SYSTEM_WEBVIEW_ENGINE) } &&
            classDef.methods.any {
                it.name == "shouldInterceptRequest" && it.returnType == WEB_RESOURCE_RESPONSE &&
                    it.parameterTypes.map(CharSequence::toString) == INTERCEPT_PARAMETERS
            }
        ) {
            bases += classDef.type
        }
    }
    val systemClient = bases.singleOrNull()
        ?: throw PatchException("$patchName: expected one SystemWebViewClient (WebViewClient subclass built from SystemWebViewEngine), found ${bases.size}")

    fun isClient(type: String): Boolean {
        var current: String? = type
        while (current != null) {
            if (current == systemClient) return true
            current = superclasses[current]
        }
        return false
    }

    val hooked = superclasses.keys.filter(::isClient).sorted().flatMap { type ->
        mutableClassDefBy(type).methods.filter { method ->
            method.name == "shouldInterceptRequest" && method.returnType == WEB_RESOURCE_RESPONSE &&
                method.implementation != null &&
                method.parameterTypes.map(CharSequence::toString) == INTERCEPT_PARAMETERS
        }
    }
    if (hooked.isEmpty()) {
        throw PatchException("$patchName: no concrete shouldInterceptRequest(WebView, WebResourceRequest) on a SystemWebViewClient")
    }
    // The base class always matches, so also require the live Ionic client when the Ionic engine
    // is there; otherwise the blocklist would silently miss every request.
    // (The Ionic ServerClient is the client class holding the IonicWebViewEngine as its outer field.)
    if (IONIC_WEBVIEW_ENGINE in superclasses && hooked.none { it.definingClass in ionicClients }) {
        throw PatchException("$patchName: the Ionic ServerClient is no longer a hookable SystemWebViewClient")
    }

    hooked.forEach { method ->
        val scratch = method.scratchRegister(patchName)
        // p2 is the request; the range form fits any register number.
        method.addInstructionsWithLabelsChecked(
            0,
            """
                invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS_DESCRIPTOR->$hook(Landroid/webkit/WebResourceRequest;)$WEB_RESOURCE_RESPONSE
                move-result-object $scratch
                if-eqz $scratch, :not_blocked
                return-object $scratch
            """,
            ExternalLabel("not_blocked", method.getInstruction(0)),
        )
    }
    return hooked
}

private fun Instruction.isExtensionCall(): Boolean =
    opcode == Opcode.INVOKE_STATIC_RANGE &&
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == EXTENSION_CLASS_DESCRIPTOR

/**
 * A register the hook may overwrite at index 0: the first local (v0) when the method has one.
 * SystemWebViewClient's own method has no locals (4.3.2: `.locals 0`); there p1, the WebView,
 * is borrowed only if the first instruction overwrites it without reading it, so its entry
 * value is dead. The other patch's hook may already sit there: its extension call reads only p2,
 * and its move-result-object p1 counts as that first write.
 */
private fun MutableMethod.scratchRegister(patchName: String): String {
    val implementation = implementation!!
    val parameterRegisters = 1 + INTERCEPT_PARAMETERS.size
    if (implementation.registerCount > parameterRegisters) return "v0"

    val p1 = implementation.registerCount - parameterRegisters + 1
    val first = implementation.instructions.first { instruction -> !instruction.isExtensionCall() }
    val overwritesP1 = (first as? OneRegisterInstruction)?.registerA == p1 && when (first.opcode) {
        Opcode.IGET_OBJECT -> (first as TwoRegisterInstruction).registerB != p1
        Opcode.SGET_OBJECT, Opcode.CONST_STRING, Opcode.NEW_INSTANCE, Opcode.CONST_4,
        Opcode.MOVE_RESULT_OBJECT -> true
        else -> false
    }
    if (!overwritesP1) {
        throw PatchException(
            "$patchName: $definingClass->shouldInterceptRequest has no free register for the hook " +
                "(no locals and p1 is not overwritten first)",
        )
    }
    return "p1"
}
