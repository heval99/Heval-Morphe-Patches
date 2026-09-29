import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.anydesk.anydeskandroid"
private const val JNI = "Lcom/anydesk/jni/JniAdExt;"

class AnyDeskSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Enable Premium forces every license wrapper around the stable JNI natives`() {
        val root = repoRoot()
        val apk = File(root, "apks/anydesk/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "9.0.0",
            patchNames = setOf("Enable Premium"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        val expected = mapOf(
            "jniIsFreeLicense" to false,
            "jniDoesLicenseAllowAccountRegistration" to true,
            "jniDoesLicenseAllowAddressBook" to true,
            "jniCanRemoveLicense" to true,
        )

        for ((native, result) in expected) {
            val wrapper = classes
                .flatMap { it.methods }
                .firstOrNull { method ->
                    method.implementation != null &&
                        method.instructions().any { insn ->
                            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                            ref?.definingClass == JNI && ref.name == native
                        }
                } ?: error("no wrapper invokes $JNI->$native()")

            assertForcedBoolean(
                wrapper,
                expected = result,
                label = "$JNI wrapper of $native()",
            )
        }

        val jni = classes.firstOrNull { it.type == JNI }
            ?: error("$JNI not found in emitted dexes")

        // The patch fails loudly if it cannot find these wrappers, so here we only
        // assert the effect: the banner type is forced to the paid value (hides the
        // free banner) and the license name to the paid label. (The native calls are
        // gone from the patched bodies, so these are located by their forced values.)
        val bannerType = jni.methods.firstOrNull { method ->
            method.implementation != null &&
                method.returnType == "I" &&
                method.parameterTypes.isEmpty() &&
                isForcedInt(method, 1)
        } ?: error("no no-arg int wrapper forced to 1 in $JNI")
        assertReturnsInt(bannerType, expected = 1, label = "$JNI banner type")

        val licenseName = jni.methods.firstOrNull { method ->
            method.implementation != null &&
                method.returnType == "Ljava/lang/String;" &&
                method.parameterTypes.isEmpty() &&
                returnsString(method, "Professional")
        } ?: error("no no-arg string wrapper returning \"Professional\" in $JNI")
        assertReturnsString(licenseName, expected = "Professional", label = "$JNI license name")
    }

    private fun isForcedInt(method: Method, expected: Int): Boolean {
        val insns = method.instructions()
        val first = insns.getOrNull(0) as? NarrowLiteralInstruction
        return first != null && first.narrowLiteral.toInt() == expected &&
            insns.getOrNull(1)?.opcode == Opcode.RETURN
    }

    private fun returnsString(method: Method, expected: String): Boolean {
        val insns = method.instructions()
        val first = insns.getOrNull(0) as? ReferenceInstruction
        val ref = first?.reference as? StringReference
        return first != null && first.opcode == Opcode.CONST_STRING &&
            ref != null && ref.string == expected &&
            insns.getOrNull(1)?.opcode == Opcode.RETURN_OBJECT
    }

    private fun assertReturnsString(method: Method, expected: String, label: String) {
        val insns = method.instructions()
        val first = insns.getOrNull(0) as? ReferenceInstruction
        val ref = first?.reference as? StringReference
        assertTrue(
            first != null && first.opcode == Opcode.CONST_STRING &&
                ref != null && ref.string == expected,
            "$label does not return \"$expected\"; first instruction is ${first?.opcode} $ref",
        )
        assertTrue(
            insns.getOrNull(1)?.opcode == Opcode.RETURN_OBJECT,
            "$label does not return immediately; second instruction is ${insns.getOrNull(1)?.opcode}",
        )
    }
}
