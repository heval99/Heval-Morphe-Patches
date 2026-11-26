import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Feeding an APK that "Spoof signature" already patched back into the same patch must fail with a
 * message that says so (Smart Launcher is the smallest user of the shared builder).
 */
class SpoofSignatureTwiceTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `a second Spoof signature run says the APK is already patched`() {
        val root = repoRoot()
        val apk = File(root, "apks/smartlauncher/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")
        val patch = loadAllPatches(newestPatchBundle(root)).single { patch ->
            patch.name == "Spoof signature" &&
                patch.compatibility.orEmpty().any { it.packageName == "ginlemon.flowerfree" }
        }

        val first = workDir.resolve("first")
        first.mkdirs()
        val patched = workDir.resolve("patched.apk")
        Patcher(PatcherConfig(apk, first.resolve("out"), validate = true)).use { patcher ->
            patcher += setOf(patch)
            val results = runBlocking { patcher.invoke().toList() }
            results.forEach { assertNull(it.exception, "first run failed: ${it.exception}") }
            val result = patcher.get()
            Files.copy(apk.toPath(), patched.toPath(), StandardCopyOption.REPLACE_EXISTING)
            result.applyTo(patched)
        }

        val second = workDir.resolve("second")
        second.mkdirs()
        Patcher(PatcherConfig(patched, second.resolve("out"), validate = true)).use { patcher ->
            patcher += setOf(patch)
            val failure = runBlocking { patcher.invoke().toList() }.single { it.patch.name == "Spoof signature" }.exception
            assertNotNull(failure, "the second run must fail, not double patch")
            assertEquals(
                "This APK is already patched with Spoof signature. " +
                    "Patch the original APK, not an installed or patched copy.",
                failure!!.message ?: failure.cause?.message,
            )
        }
    }
}
