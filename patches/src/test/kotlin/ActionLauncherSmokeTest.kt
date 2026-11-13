import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.actionlauncher.playstore"
private const val VERSION = "53.0"
private const val PLUS_PURCHASE_CODE = 0x10382
private const val PLUS_KEY = "al3_plus"

class ActionLauncherSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Enable Premium forces the Plus purchase check true`() {
        val root = repoRoot()
        val apk = File(root, "apks/actionlauncher/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = VERSION,
            patchNames = setOf("Enable Premium"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        val readers = classes.flatMap { it.methods }.filter { method ->
            method.returnType == "Z" &&
                method.parameterTypes.isEmpty() &&
                method.implementation?.instructions?.any { instruction ->
                    instruction is NarrowLiteralInstruction && instruction.narrowLiteral == PLUS_PURCHASE_CODE
                } == true
        }
        check(readers.size == 1) { "Plus readers: ${readers.map { "${it.definingClass}->${it.name}" }}" }
        val method = readers.single()
        assertForcedBoolean(method, expected = true, label = "${method.definingClass}->${method.name}")

        // The billing flow still knows the SKU it maps onto the Plus code.
        val billing = classes.flatMap { it.methods }.filter { candidate ->
            candidate.implementation?.instructions?.any { insn ->
                (insn as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                    ?.reference is com.android.tools.smali.dexlib2.iface.reference.StringReference &&
                    ((insn as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                        .reference as com.android.tools.smali.dexlib2.iface.reference.StringReference).string == PLUS_KEY
            } == true
        }
        check(billing.isNotEmpty()) { "the $PLUS_KEY purchase mapping vanished" }
    }
}
