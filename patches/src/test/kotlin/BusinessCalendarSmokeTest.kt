import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.appgenix.bizcal"
private const val ADS_UTIL = "Lcom/appgenix/bizcal/util/AdsUtil;"

class BusinessCalendarSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable ads forces the user ad gate off`() {
        val root = repoRoot()
        val apk = File(root, "apks/bizcal/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "2.55.5",
            patchNames = setOf("Disable ads"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        val adsUtil = classes.firstOrNull { it.type == ADS_UTIL }
            ?: error("$ADS_UTIL not found in emitted dexes")

        assertForcedBoolean(
            adsUtil.method("showAdsForUser", listOf("Landroid/content/Context;")),
            expected = false, label = "AdsUtil.showAdsForUser(Context)",
        )
    }
}
