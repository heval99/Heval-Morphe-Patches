import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BraveNewsOriginSmokeTest {

    @TempDir
    lateinit var workDir: File

    // Brave Origin rewrites the News policy check to read its own pref and drops the original
    // string; Turn off Brave News must still apply next to it and end with the check forced true.
    @Test
    fun `Brave Origin and Turn off Brave News apply together`() {
        val root = repoRoot()
        val apk = File(root, "apks/brave/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = "com.brave.browser",
            version = "1.100.59",
            patchNames = setOf("Brave Origin", "Turn off Brave News"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val hits = classes.flatMap { it.methods }.filter { m ->
            m.implementation != null && m.returnType == "Z" &&
                m.parameterTypes.map { it.toString() } == listOf("Lorg/chromium/chrome/browser/profiles/Profile;") &&
                m.instructions().any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                        ?.endsWith("BraveNewsDisabled") == true
                }
        }
        assertEquals(1, hits.size, "Origin's rewritten News check: ${hits.map { "${it.definingClass}->${it.name}" }}")
        assertForcedBoolean(hits.single(), true, "Brave News policy check with Brave Origin")
    }
}
