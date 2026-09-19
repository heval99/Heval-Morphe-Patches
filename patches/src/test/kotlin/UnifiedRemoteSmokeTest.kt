import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.Relmtech.Remote"

class UnifiedRemoteSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Enable Pro forces the RevenueCat entitlement active`() {
        val root = repoRoot()
        val apk = File(root, "apks/unifiedremote/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "3.25.1",
            patchNames = setOf("Enable Pro"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        val entitlementInfo = classes.firstOrNull { it.type == "Lcom/revenuecat/purchases/EntitlementInfo;" }
            ?: error("RevenueCat EntitlementInfo not found in emitted dexes")

        assertForcedBoolean(
            entitlementInfo.method("isActive"),
            expected = true,
            label = "EntitlementInfo.isActive()",
        )
    }
}
