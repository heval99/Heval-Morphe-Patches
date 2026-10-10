import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.foobnix.pdf.reader"
private const val APP_CONFIG = "Lcom/foobnix/pdf/info/AppsConfig;"
private const val ADS = "Lcom/foobnix/pdf/info/ADS;"

class LibreraSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable ads forces the ad gate off and the reward window open`() {
        val root = repoRoot()
        val apk = File(root, "apks/librera/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "9.4.20",
            patchNames = setOf("Disable ads"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        fun klass(type: String) = classes.firstOrNull { it.type == type }
            ?: error("$type not found in emitted dexes")

        assertForcedBoolean(
            klass(APP_CONFIG).method("isShowAdsInApp", listOf("Landroid/content/Context;")),
            expected = false, label = "AppsConfig.isShowAdsInApp(Context)",
        )
        assertForcedBoolean(
            klass(ADS).method("isRewardActivated", emptyList()),
            expected = true, label = "ADS.isRewardActivated()",
        )
    }
}
