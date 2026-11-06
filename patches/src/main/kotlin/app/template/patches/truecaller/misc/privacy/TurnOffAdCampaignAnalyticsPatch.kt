package app.template.patches.truecaller.misc.privacy

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.Constants.COMPATIBILITY_TRUECALLER
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.requireNoBranchTargetAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// The Privacy Center toggle's backing preference ("Ad Campaign Analytics" in the UI). It is read
// through a generic getBoolean(settings, key, default) helper in three places: the settings row
// state (mfi.c), the verified-campaign clickstream gate (py5.c) and the carousel clickstream gate
// (iz80.a). The toggle writer (r4y.onCheckedChanged) uses putBoolean and is left alone.
private const val VC_EVENTS_KEY = "biz_privacy_vc_events"

/**
 * "Ad Campaign Analytics" (#125): Truecaller's Privacy Center row "Share analytics with verified
 * businesses on my interactions with ad campaigns" is on by default. Turning it off by default
 * means the three getBoolean reads above must pass `false` as their fallback; the toggle stays
 * usable, so the user can still opt in (and putBoolean keeps storing the choice).
 *
 * The reads do not share one shape: two pass a dedicated `const/4 vX, 0x1` right before the call,
 * while the settings-state mapper reuses one `const/4 v8, 0x1` for several unrelated preference
 * defaults. The patch therefore changes the register that holds the boolean argument of each
 * getBoolean call: it rewrites that register's own `const` when it sits right before the call,
 * otherwise it inserts `const/4 vX, 0x0` at the call (v8 in 26.43.5, not read again afterwards).
 */
@Suppress("unused")
val turnOffAdCampaignAnalyticsPatch = bytecodePatch(
    name = "Turn off Ad Campaign Analytics",
    description = "Turns the \"Ad Campaign Analytics\" privacy toggle off by default. You can still " +
        "turn it on in Settings > Privacy Center.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_TRUECALLER)

    execute {
        // Collect first: mutating classes while iterating them is not allowed.
        val readers = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                if (method.readsVcEventsDefault()) readers += classDef to method
            }
        }
        if (readers.size != 3) throw PatchException(
            "expected the three \"$VC_EVENTS_KEY\" getBoolean readers, found ${readers.size}: " +
                readers.joinToString { (classDef, method) -> "${classDef.type}->${method.name}" },
        )

        for ((classDef, method) in readers) {
            val mutable = mutableClassDefBy(classDef).methods.single {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
            }
            try {
                mutable.defaultVcEventsToOff("${classDef.type}->${method.name}")
            } catch (e: PatchException) {
                throw PatchException("${classDef.type}->${method.name}: ${e.message ?: "no detail"}", e)
            }
        }
    }
}

/** True for a method that reads the preference through the generic getBoolean helper. */
private fun Method.readsVcEventsDefault(): Boolean {
    val insns = implementation?.instructions?.toList() ?: return false
    val keyIndex = insns.indexOfFirst { it.stringOrNull() == VC_EVENTS_KEY }
    if (keyIndex < 0) return false
    val keyRegister = (insns[keyIndex] as OneRegisterInstruction).registerA
    return insns.any { it.getBooleanCall() != null && it.keyArgument() == keyRegister }
}

/** Passes `false` as the getBoolean fallback of the VC-events read in this method. */
private fun MutableMethod.defaultVcEventsToOff(label: String) {
    val insns = instructions.toList()
    val keyIndex = insns.indexOfFirst { it.stringOrNull() == VC_EVENTS_KEY }
    if (keyIndex < 0) throw PatchException("$label: the \"$VC_EVENTS_KEY\" string is gone")
    val keyRegister = (insns[keyIndex] as OneRegisterInstruction).registerA

    // The read whose second register (settings, key, boolean) is that key string.
    val call = insns.indexOfFirst { insn ->
        insn.getBooleanCall() != null && insn.keyArgument() == keyRegister
    }
    if (call < 0) throw PatchException("$label: no getBoolean call reads v$keyRegister as its key")

    // The boolean argument is the third register.
    val register = when (val invoke = insns[call]) {
        is FiveRegisterInstruction -> invoke.registerE
        is RegisterRangeInstruction -> invoke.startRegister + 2
        else -> throw PatchException("$label: getBoolean is not an invoke with registers (${insns[call].opcode})")
    }

    val constOp = if (register < 16) "const/4" else "const/16"
    val previous = insns.getOrNull(call - 1)
    val previousIsFallback = previous is NarrowLiteralInstruction &&
        previous.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16) &&
        (previous as OneRegisterInstruction).registerA == register && previous.narrowLiteral == 1
    if (previousIsFallback) {
        replaceInstruction(call - 1, "$constOp v$register, 0x0")
    } else {
        requireNoBranchTargetAt(call, "$label VC-events default")
        addInstructionsChecked(call, "$constOp v$register, 0x0")
    }
}

private fun Instruction.keyArgument(): Int = when (this) {
    is FiveRegisterInstruction -> if (registerCount == 3) registerD else -1
    is RegisterRangeInstruction -> if (registerCount == 3) startRegister + 1 else -1
    else -> -1
}

/**
 * The generic settings boolean getter: a static `(settings, String key, boolean fallback)Z` call.
 * Both the holder class and the method name are R8-renamed, so it is found by shape; the key
 * string on the call site ties it to this preference.
 */
private fun Instruction.getBooleanCall(): MethodReference? {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return null
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (reference.returnType != "Z") return null
    val params = reference.parameterTypes.map(CharSequence::toString)
    if (params.size != 3 || params[1] != "Ljava/lang/String;" || params[2] != "Z") return null
    if (!params[0].startsWith("L") || params[0] == "Ljava/lang/String;") return null
    return reference
}

private fun Instruction.stringOrNull(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
