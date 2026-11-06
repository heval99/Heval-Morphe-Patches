package app.template.patches.truecaller.misc.privacy

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECALLER
import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.addHevalGate
import app.template.patches.truecaller.controls.TruecallerControlsCategory
import app.template.patches.truecaller.controls.truecallerControls
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * One Privacy Center preference: the string key its boolean is stored under and the value the
 * privacy-preserving default takes. Some keys are "disabled" flags (true means private), others are
 * "opt in" flags (false means private).
 */
private class PrivacyDefault(
    val feature: HevalFeature,
    val key: String,
    val privateValue: Boolean,
)

private val PRIVACY_DEFAULTS = listOf(
    PrivacyDefault(
        HevalFeature(
            id = "truecaller.privacy_availability",
            name = "Hide my availability",
            description = "Stops Truecaller telling other users that you are busy or in silent mode.",
            category = TruecallerControlsCategory.PRIVACY,
        ),
        key = "availability_disabled",
        privateValue = true,
    ),
    PrivacyDefault(
        HevalFeature(
            id = "truecaller.privacy_profile_views",
            name = "No profile view notifications",
            description = "Turns off the notification that says someone viewed your profile.",
            category = TruecallerControlsCategory.PRIVACY,
        ),
        key = "showProfileViewNotifications",
        privateValue = false,
    ),
    PrivacyDefault(
        HevalFeature(
            id = "truecaller.privacy_fraud_messages",
            name = "Don't share fraud messages",
            description = "Stops Truecaller uploading messages it flags as fraud.",
            category = TruecallerControlsCategory.PRIVACY,
        ),
        key = "fraudMessageLoggingOptIn",
        privateValue = false,
    ),
    PrivacyDefault(
        HevalFeature(
            id = "truecaller.privacy_social_graph",
            name = "Disable social graph",
            description = "Stops Truecaller analysing your call patterns to enrich incoming calls.",
            category = TruecallerControlsCategory.PRIVACY,
        ),
        key = "supernovaOptIn",
        privateValue = false,
    ),
    PrivacyDefault(
        HevalFeature(
            id = "truecaller.privacy_business_call_logs",
            name = "Don't share business call logs",
            description = "Stops sharing your verified-business call log with those businesses.",
            category = TruecallerControlsCategory.PRIVACY,
        ),
        key = "verifiedBusinessCallLogsOptIn",
        privateValue = false,
    ),
)

/** The five Privacy Center switches, in registry order. */
internal val PRIVACY_FEATURES = PRIVACY_DEFAULTS.map { it.feature }

/**
 * Privacy Center defaults (#125 follow-up). Every toggle in Truecaller's Privacy Center is a boolean
 * preference read through the same generic `getBoolean(settings, key, fallback)` helper the Ad
 * Campaign Analytics patch uses; the Privacy Center screen, the workers and the feature gates all go
 * through it, while the toggle writer uses `putBoolean` and is left alone.
 *
 * The patch gives each switch a runtime gate in front of every `getBoolean` read of its key: while
 * the switch is on, the fallback passed to the read is the private value, so a preference the user
 * never touched reads as private; a choice the user has made is still stored and still wins.
 *
 * The switches are on by default (they are the privacy-preserving state), and each can be turned
 * off in Patch settings > Privacy to get the stock default back.
 */
@Suppress("unused")
val privacyDefaultsPatch = bytecodePatch(
    name = "Privacy defaults",
    description = "Turns the Privacy Center sharing options off unless you opt in.",
) {
    compatibleWith(COMPATIBILITY_TRUECALLER)

    dependsOn(truecallerControls.patch)

    execute {
        // One pass, collecting the readers of every key. Mutating happens afterwards.
        val sites = PRIVACY_DEFAULTS.associateWith { mutableListOf<Pair<ClassDef, Method>>() }
        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                val insns = method.implementation?.instructions?.toList() ?: return@forEach
                for (default in PRIVACY_DEFAULTS) {
                    if (insns.readsKey(default.key)) sites.getValue(default) += classDef to method
                }
            }
        }

        for (default in PRIVACY_DEFAULTS) {
            val found = sites.getValue(default)
            if (found.isEmpty()) {
                throw PatchException("Privacy defaults: no getBoolean reader of \"${default.key}\" found")
            }
            for ((classDef, method) in found) {
                val mutable = mutableClassDefBy(classDef).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }
                try {
                    mutable.gateDefault(default)
                } catch (e: PatchException) {
                    throw PatchException("${classDef.type}->${method.name} (${default.key}): ${e.message}", e)
                }
            }
            truecallerControls.register(default.feature)
        }
    }
}

/** True when [instructions] read [key] through either boolean getter shape. */
private fun List<Instruction>.readsKey(key: String): Boolean {
    val keyIndex = indexOfFirst { it.stringOrNull() == key }
    if (keyIndex < 0) return false
    val keyRegister = (this[keyIndex] as OneRegisterInstruction).registerA
    return indices.any { index -> this[index].isBooleanGet() && this[index].keyArgument() == keyRegister }
}

/**
 * Gates every read of the key in this method (coroutine state machines hold one per branch). The
 * gate writes the private value into the fallback register and falls into the stock call, so the
 * stored choice is still read and still wins.
 */
private fun MutableMethod.gateDefault(default: PrivacyDefault) {
    val insns = instructions.toList()
    val literal = if (default.privateValue) 1 else 0
    var gated = 0
    for (keyIndex in insns.indices.filter { insns[it].stringOrNull() == default.key }) {
        val keyRegister = (insns[keyIndex] as OneRegisterInstruction).registerA
        val calls = insns.indices.filter { index ->
            insns[index].isBooleanGet() && insns[index].keyArgument() == keyRegister
        }.reversed()
        for (call in calls) {
            val fallback = when (val invoke = insns[call]) {
                is FiveRegisterInstruction -> invoke.registerE
                is RegisterRangeInstruction -> invoke.startRegister + 2
                else -> throw PatchException("getBoolean is not an invoke with registers (${insns[call].opcode})")
            }
            val op = if (fallback < 16) "const/4" else "const/16"
            addHevalGate(default.feature.id, index = call, register = fallback) { _ ->
                "$op v$fallback, 0x$literal"
            }
            gated++
        }
    }
    if (gated == 0) throw PatchException("no boolean read of \"${default.key}\" was gated")
}

private fun Instruction.keyArgument(): Int = when (this) {
    is FiveRegisterInstruction -> if (registerCount == 3) registerD else -1
    is RegisterRangeInstruction -> if (registerCount == 3) startRegister + 1 else -1
    else -> -1
}

/**
 * True for both boolean getter shapes Truecaller uses: the generic settings helper
 * `(settings, String key, boolean fallback)Z` (class and method R8-renamed, found by shape) and the
 * plain `SharedPreferences.getBoolean(String key, boolean fallback)Z`. In both, of the three
 * registers the second is the key and the third the fallback.
 */
private fun Instruction.isBooleanGet(): Boolean {
    if (opcode !in setOf(
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
        )
    ) return false
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (reference.returnType != "Z") return false
    val params = reference.parameterTypes.map(CharSequence::toString)
    if (reference.definingClass == "Landroid/content/SharedPreferences;") {
        return params.size == 2 && params[0] == "Ljava/lang/String;" && params[1] == "Z"
    }
    return params.size == 3 && params[1] == "Ljava/lang/String;" && params[2] == "Z" &&
        params[0].startsWith("L") && params[0] != "Ljava/lang/String;"
}

private fun Instruction.stringOrNull(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string



