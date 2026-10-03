package app.template.patches.anydesk.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.Constants.COMPATIBILITY_ANYDESK
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val JNI = "Lcom/anydesk/jni/JniAdExt;"

/**
 * The JNI class keeps its native `jni*` entry points stable but R8 rotates the Java wrapper
 * names every release (the previous patch pinned r3/a2/b2/Q1, which on 9.0.0 are unrelated
 * helpers - the patch silently no-opped). Anchor on the native call instead.
 *
 * Two layers are forced:
 * - Feature gates: every no-arg boolean wrapper that invokes one of the license natives
 *   is forced to the paid result (account registration, address book, remove license,
 *   and the free-license flag itself).
 * - Display state: the license name (`u2()` on 9.0.0, shown as "License: <name>" in
 *   About) and the banner type (`q2()`, which hides the free banner when it reports
 *   the paid value 1) both read the native license state directly, so they are forced
 *   too - otherwise the app still *says* "free license" with the features unlocked.
 */
private val LICENSE_GATES = mapOf(
    "jniIsFreeLicense" to false,
    "jniDoesLicenseAllowAccountRegistration" to true,
    "jniDoesLicenseAllowAddressBook" to true,
    "jniCanRemoveLicense" to true,
)

// The classic AnyDesk paid license name shown in About. The native engine only ever
// reports the free name on an unlicensed install (Play/microG can't register one),
// so there is no "true" value to discover - this is display-only.
private const val PAID_LICENSE_NAME = "Professional"

// Banner type value that hides the free-license banner (the banner is only shown
// when this differs from 1).
private const val PAID_BANNER_TYPE = 1

private fun MutableMethod.invokesNative(name: String): Boolean =
    implementation?.instructions?.any { instruction ->
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        ref != null && ref.definingClass == JNI && ref.name == name
    } == true

@Suppress("unused")
val enablePremiumPatch = bytecodePatch(
    name = "Enable Premium",
    description = "Enables premium features by making the app treat the free license as paid."
) {
    compatibleWith(COMPATIBILITY_ANYDESK)

    execute {
        classDefForEach { classDef ->
            mutableClassDefBy(classDef).methods
                .filter { it.implementation != null }
                .filter { it.returnType == "Z" && it.parameterTypes.isEmpty() }
                .forEach { method ->
                    val gate = method.implementation!!.instructions
                        .mapNotNull { insn ->
                            (insn as? ReferenceInstruction)?.reference as? MethodReference
                        }
                        .firstOrNull { ref ->
                            ref.definingClass == JNI && LICENSE_GATES.containsKey(ref.name)
                        }
                    if (gate != null) method.returnEarly(LICENSE_GATES.getValue(gate.name))
                }
        }

        val jni = mutableClassDefByOrNull(JNI)
            ?: error("AnyDesk JNI class $JNI not found")

        // Banner type gate: the wrapper calling jniGetLicenseBannerType is forced to
        // the paid value so the free banner stays hidden.
        val bannerType = jni.methods.firstOrNull { method ->
            method.implementation != null &&
                method.returnType == "I" &&
                method.parameterTypes.isEmpty() &&
                method.invokesNative("jniGetLicenseBannerType")
        } ?: error("AnyDesk license banner type wrapper not found")
        bannerType.returnEarly(PAID_BANNER_TYPE)

        // License name shown in About ("License: <name>"): the wrapper calling
        // jniGetLicenseName always reports the paid name.
        val licenseName = jni.methods.firstOrNull { method ->
            method.implementation != null &&
                method.returnType == "Ljava/lang/String;" &&
                method.parameterTypes.isEmpty() &&
                method.invokesNative("jniGetLicenseName")
        } ?: error("AnyDesk license name wrapper not found")
        licenseName.removeInstructions(0, licenseName.implementation!!.instructions.count())
        licenseName.addInstructions(
            0,
            """
                const-string v0, "$PAID_LICENSE_NAME"
                return-object v0
            """.trimIndent(),
        )
    }
}
