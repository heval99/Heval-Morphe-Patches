import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.fenchtose.reflog"
private const val KEEPER = "Lcom/fenchtose/reflog/features/purchase/d;"

class TaskitoSmokeTest {

    @TempDir
    lateinit var workDir: File

    /** The no-arg Boolean getter that reads the `premium` preference, found like the patch. */
    private fun premiumGetters(classes: List<ClassDef>) = classes
        .filter { it.type == KEEPER }
        .flatMap { it.methods }
        .filter { method ->
            method.returnType == "Z" && method.parameterTypes.isEmpty() && method.implementation != null &&
                method.instructions().mapNotNull { instruction ->
                    (instruction as? ReferenceInstruction)?.reference as? StringReference
                }.any { it.string == "premium" }
        }

    @Test
    fun `Enable Premium forces the purchase keeper to report premium`() {
        val root = repoRoot()
        val apk = File(root, "apks/taskito/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val stock = premiumGetters(patchedApkClasses(apk))
        assertEquals(1, stock.size, "premium getters on the keeper: ${stock.map { it.name }}")
        val getter = stock.single()

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "1.3.3",
            patchNames = setOf("Enable Premium"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val patched = classes.filter { it.type == KEEPER }.flatMap { it.methods }
            .single { it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == "Z" }
        assertForcedBoolean(patched, expected = true, label = "$KEEPER->${patched.name}()Z")
    }
}
