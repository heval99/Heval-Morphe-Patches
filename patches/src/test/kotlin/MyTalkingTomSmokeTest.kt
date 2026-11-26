import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MyTalkingTomSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable ads patches the inventory binding and the splash loader`() {
        val root = repoRoot()
        val apk = File(root, "apks/mytalkingtom/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = "com.outfit7.mytalkingtomfree",
            version = "26.5.4.9664",
            patchNames = setOf("Disable ads"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        assertOutfit7AdsDisabled(classes)
    }
}
