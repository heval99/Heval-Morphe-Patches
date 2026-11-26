import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MyTalkingTom2SmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable ads patches the inventory binding and the splash loader`() {
        val root = repoRoot()
        val apk = File(root, "apks/mytalkingtom2/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = "com.outfit7.mytalkingtom2",
            version = "26.5.2.29411",
            patchNames = setOf("Disable ads"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        assertOutfit7AdsDisabled(classes)
    }
}
