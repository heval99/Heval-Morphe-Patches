// Based on bearinmindcat/morphe-patches (GPL-3.0) @ 5f79152, patches/src/main/kotlin/org/ungoogled/patches/samsungkeyboard/misc/EnableNonOneUiPatch.kt.
// Modified for Heval's Morphe Patches on 2026-11-25: split out of EnableNonOneUiPatch.kt so Change package name carries it too.

package app.template.patches.samsungkeyboard.misc

import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.addInstructionsWithLabelsChecked
import app.template.patches.shared.samsung.redirectCalls
import app.template.patches.shared.samsung.redirectOneUiFrameworkLookups
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val ONE_UI = "Lapp/template/extension/samsungkeyboard/OneUi;"
private const val LOCAL_CLIPBOARD = "Lapp/template/extension/samsungkeyboard/LocalClipboard;"

/** Logged by the keyboard's check of which packages may read its clipboard history (Samsung's, by name). */
private const val CLIPBOARD_CALLER_REFUSED = ") can't access to clipboard."

/** Logged by the keyboard's check that a caller of its providers is signed by Samsung. */
private const val SIGNATURE_CHECK_FAILED = "certification failed"
private const val SIGNATURE_CHECK_PASSED = "valid signature : "
private const val KEYBOARD_SERVICE_TYPE = "Lcom/samsung/android/honeyboard/service/HoneyBoardService;"

private val log = java.util.logging.Logger.getLogger("SamsungKeyboard")

/**
 * What the keyboard needs on any ROM once it is no longer the system app (Change package name, or Enable non-One UI
 * ROM support, which both depend on this): its own providers' Samsung-signer checks and its clipboard's caller list
 * (both named after Samsung's packages and signature), its dialogs' system window type (an ordinary app may not add
 * one) and its resource lookups under the stock package name (the resource table moves with the package). Each is
 * a no-op where the keyboard is still the system app: OneUi leaves the window alone when it may add it, and the
 * layout-limit part of setFlags only acts off One UI.
 */
internal val renamedPackageFixesPatch = bytecodePatch(
    description = "Lets the keyboard's clipboard, providers and dialogs work under another package name.",
) {
    dependsOn(systemAccessPatch)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        // The dialogs are input method dialogs; OneUi attaches them to the keyboard's window where the app may not use them.
        val windowFlags = redirectCalls(ONE_UI, mapOf("Landroid/view/Window;" to setOf("setFlags(II)V", "setType(I)V")))
        val lookups = redirectOneUiFrameworkLookups(ONE_UI)
        if (classDefByOrNull(KEYBOARD_SERVICE_TYPE) == null) throw PatchException("$KEYBOARD_SERVICE_TYPE not found")
        mutableClassDefBy(KEYBOARD_SERVICE_TYPE).methods.firstOrNull { it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V" }
            ?.addInstructionsChecked(0, "invoke-static { p0 }, $ONE_UI->keyboardCreated(Landroid/inputmethodservice/InputMethodService;)V")
            ?: throw PatchException("$KEYBOARD_SERVICE_TYPE.onCreate not found")

        // LocalClipboard adds copies to the keyboard's history through its clipboard provider, which only trusts callers
        // signed by Samsung: the keyboard's own calls are trusted too.
        val checks = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(checks) { m ->
                m.returnType == "Z" && m.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Ljava/lang/String;") &&
                    m.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                        ?.let { SIGNATURE_CHECK_FAILED in it && SIGNATURE_CHECK_PASSED in it } == true
            }
        }
        // One copy per module (the clipboard's and the settings'); 5.9.40.75 has two.
        if (checks.isEmpty()) throw PatchException("provider signature check not found")
        for (check in checks) {
            mutableClassDefBy(check.definingClass).methods.first {
                it.name == check.name && it.parameterTypes == check.parameterTypes && it.returnType == check.returnType
            }.apply {
                val static = AccessFlags.STATIC.isSet(accessFlags)
                val (context, caller) = if (static) "p0" to "p1" else "p1" to "p2"
                if (implementation!!.registerCount - (if (static) 2 else 3) < 1) throw PatchException("no free register in ${check.name}")
                addInstructionsWithLabelsChecked(
                    0,
                    """
                        invoke-static { $context, $caller }, $LOCAL_CLIPBOARD->isSelf(Landroid/content/Context;Ljava/lang/String;)Z
                        move-result v0
                        if-eqz v0, :check
                        return v0
                    """,
                    ExternalLabel("check", getInstruction(0)),
                )
            }
        }

        // Its clipboard history answers Samsung's package names only, its own included: under a new package name the
        // keyboard was refused its own history.
        val clipboardCallers = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(clipboardCallers) { m ->
                m.returnType == "Z" && m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                    !AccessFlags.STATIC.isSet(m.accessFlags) &&
                    m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == CLIPBOARD_CALLER_REFUSED } == true
            }
        }
        if (clipboardCallers.isEmpty()) throw PatchException("clipboard caller check not found")
        for (check in clipboardCallers) {
            mutableClassDefBy(check.definingClass).methods.first {
                it.name == check.name && it.parameterTypes == check.parameterTypes && it.returnType == check.returnType
            }.apply {
                if (implementation!!.registerCount - 2 < 1) throw PatchException("no free register in ${check.name}")
                addInstructionsWithLabelsChecked(
                    0,
                    """
                        invoke-static { p1 }, $LOCAL_CLIPBOARD->isSelfPackage(Ljava/lang/String;)Z
                        move-result v0
                        if-eqz v0, :check
                        return v0
                    """,
                    ExternalLabel("check", getInstruction(0)),
                )
            }
        }

        log.info(
            "Renamed package fixes: window flags and types: $windowFlags; lookups by name: $lookups; provider signature " +
                "checks: ${checks.size}; clipboard caller checks: ${clipboardCallers.size}",
        )
        // Measured against 5.9.40.75: 16 flag calls, 12 window types, 78 lookups by name.
        if (windowFlags < 15) throw PatchException("only $windowFlags window flag and type calls found")
        if (lookups < 5) throw PatchException("only $lookups resource lookups by name found")
    }
}
