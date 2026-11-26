// Based on bearinmindcat/morphe-patches (GPL-3.0) @ 5f79152, patches/src/main/kotlin/org/ungoogled/patches/samsungkeyboard/misc/EnableNonOneUiPatch.kt.
// Modified for Heval's Morphe Patches on 2026-11-13: package and extension prefix renamed, checked instruction helpers.

package app.template.patches.samsungkeyboard.misc

import app.template.patches.shared.samsung.EXTENSION_CLASS_PREFIX

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.template.patches.shared.samsung.convertSprDrawables
import app.template.patches.shared.samsung.makeLibrariesOptional
import app.template.patches.shared.samsung.redirectOneUiMembers
import app.template.patches.shared.Constants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.w3c.dom.Element

private const val ONE_UI = "Lapp/template/extension/samsungkeyboard/OneUi;"
private const val SERVICES = "Lapp/template/extension/samsungkeyboard/Services;"
private const val KEYBOARD_SERVICE = "com.samsung.android.honeyboard.service.HoneyBoardService"

private val log = java.util.logging.Logger.getLogger("SamsungKeyboard")

/**
 * One UI framework resources the keyboard looks up by name and uses unchecked, as "type/name" to their value off One
 * UI; each is added as "oneui_<name>", which OneUi.getIdentifier answers with (as in Clock, see
 * samsung/OneUiFrameworkResources). The settings page reserves room for One UI's task bar: there is none here.
 */
private val FRAMEWORK_RESOURCES = mapOf("dimen/task_bar_height" to "0dp")

/**
 * Samsung's own services the keyboard needs off One UI; Services answers them: stand-ins for the two it casts without
 * a null check, LocalClipboard for the clipboard service.
 */
private val SAMSUNG_SERVICES = setOf("sem_statusbar", "desktopmode", "semclipboard")
private val CONTEXTS = setOf(
    "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/view/ContextThemeWrapper;",
    "Landroid/app/Activity;", "Landroid/app/Application;", "Landroid/app/Service;", "Landroid/inputmethodservice/InputMethodService;",
)

/**
 * The manifest of the system keyboard: its shared user id (android.uid.honeyboard, Samsung's signature) and coreApp go;
 * Samsung's libraries it names are optional already.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val services = manifest.getElementsByTagName("service")
            val ime = (0 until services.length).any { (services.item(it) as Element).getAttribute("android:name") == KEYBOARD_SERVICE }
            if (!ime) throw PatchException("$KEYBOARD_SERVICE not in the manifest")
            manifest.documentElement.removeAttribute("android:sharedUserId")
            manifest.documentElement.removeAttribute("coreApp")
        }
        log.info("Required Samsung libraries made optional: ${makeLibrariesOptional()}")

        val types = FRAMEWORK_RESOURCES.keys.map { it.substringBefore('/') }.toSet()
        decode(*types.map { "res/values/${it}s.xml" }.toTypedArray())
        for (type in types) {
            document("res/values/${type}s.xml").use { xml ->
                for ((key, value) in FRAMEWORK_RESOURCES.filterKeys { it.startsWith("$type/") }) {
                    xml.documentElement.appendChild(xml.createElement(type).apply {
                        setAttribute("name", "oneui_" + key.substringAfter('/'))
                        textContent = value
                    })
                }
            }
        }
    }
}

/** Many icons are SPR files, Samsung's vector format that only One UI can draw (Manage input languages died on one). */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        log.info("SPR drawables: $converted")
        // Measured against 5.9.40.75: 78 files.
        if (converted < 30) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Sends the keyboard's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers; where
 * the phone has the member, OneUi calls it) and the
 * service lookups of [SAMSUNG_SERVICES] through Services (with the clipboard history fed by LocalClipboard). Runs
 * after the system-access shims, which take One UI's per-user settings read themselves.
 */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    dependsOn(systemAccessPatch, renamedPackageFixesPatch)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(ONE_UI, "Lapp/template/extension/samsungkeyboard/Sem;")

        // Only the methods that name one of the unchecked services: every other lookup stays as it is.
        fun Method.names() = implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string in SAMSUNG_SERVICES
        } ?: false
        fun Any.isLookup() = this is ReferenceInstruction &&
            (opcode == Opcode.INVOKE_VIRTUAL) && (reference as? MethodReference)?.let {
                it.definingClass in CONTEXTS && it.name == "getSystemService" && it.returnType == "Ljava/lang/Object;" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
            } == true
        val sites = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith(EXTENSION_CLASS_PREFIX)) return@classDefForEach
            c.methods.filter { it.names() }.forEach { sites += c.type to it }
        }
        var services = 0
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (!insn.isLookup()) continue
                val i = insn as FiveRegisterInstruction
                mutable.replaceInstruction(
                    index,
                    "invoke-static { v${i.registerC}, v${i.registerD} }, " +
                        "$SERVICES->get(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Object;",
                )
                services++
            }
        }

        log.info("One UI members: $calls calls, $fields fields; Samsung services: $services")
        // Measured against 5.9.40.75: 227 calls, 24 field reads, 14 service lookups.
        if (calls < 100 || fields < 10) throw PatchException("only $calls One UI calls and $fields field accesses found")
        if (services < 5) throw PatchException("only $services Samsung service lookups found")
    }
}

/**
 * Off One UI the keyboard dies as it starts, on SemSystemProperties (a One UI class). The extension carries stand-ins
 * for the 47 One UI classes it uses, answering "absent"; on a Samsung phone the framework's own classes load first, so
 * they never run there, and OneUi calls One UI's members where the phone has them.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Keyboard usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    dependsOn(manifestPatch, sprDrawablesPatch, oneUiMembersPatch, systemAccessPatch, android13Patch, languageDownloadsPatch, storeLinksPatch)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)
}
