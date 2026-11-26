import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

private const val PKG = "com.camerasideas.trimmer"
private const val VERSION = "1.730.1226"
private const val VERSION_CODE = 1226
private const val LIB_ARM64 = "lib/arm64-v8a/libisvideoengine.so"
private const val LIB_V7A = "lib/armeabi-v7a/libisvideoengine.so"

private val PATCHES = setOf(
    "Disable native signature check",
    "Enable Premium",
    "Disable ads",
    "Disable telemetry",
)

private val MOV_W1_SIGKILL = byteArrayOf(0x21, 0x01, 0x80.toByte(), 0x52)
private val NOP = byteArrayOf(0x1F, 0x20, 0x03, 0xD5.toByte())

private fun ByteArray.indexOfSequence(sequence: ByteArray, start: Int = 0): Int {
    outer@ for (i in start..size - sequence.size) {
        for (j in sequence.indices) {
            if (this[i + j] != sequence[j]) continue@outer
        }
        return i
    }
    return -1
}

private fun ByteArray.sigkillCalls(): List<Int> {
    val bytes = this
    return buildList {
        var i = bytes.indexOfSequence(MOV_W1_SIGKILL)
        while (i >= 0) {
            if (i + 8 <= bytes.size && (bytes[i + 7].toInt() and 0xFC) == 0x94) add(i)
            i = bytes.indexOfSequence(MOV_W1_SIGKILL, i + 1)
        }
    }
}

class YouCutSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `all YouCut patches apply and block the gates`() {
        val root = repoRoot()
        val apk = File(root, "apks/youcut/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val stockLib = ZipFile(apk).use { zip ->
            val entry = zip.getEntry(LIB_ARM64) ?: error("$LIB_ARM64 missing from ${apk.path}")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val stockKills = stockLib.sigkillCalls()
        check(stockKills.size == 1) {
            val at = stockLib.indexOfSequence(MOV_W1_SIGKILL)
            "expected one SIGKILL call in the stock library, found ${stockKills.size}; " +
                "size=${stockLib.size} first=$at " +
                "bytes=" + (if (at >= 0) stockLib.copyOfRange(at, at + 8).joinToString("") { "%02x".format(it) } else "-")
        }
        val killOffset = stockKills.single()

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = VERSION,
            patchNames = PATCHES,
            allPatches = loadAllPatches(newestPatchBundle(root)),
            versionCode = VERSION_CODE,
        )
        val byType = classes.associateBy { it.type }
        val diagnostics = "emitted classes=${classes.size}"

        fun klass(type: String): ClassDef = byType[type] ?: error("$type not found\n$diagnostics")
        fun method(type: String, name: String, params: List<String>? = null) =
            klass(type).method(name, params)

        // Enable Premium: the app-wide Pro gate returns true.
        // Found by its preference-key string like the patch does (the class rotates: billing/d
        // in 1.722.1225, billing/c in 1.730.1226).
        val proGate = classes.flatMap { it.methods }.single { m ->
            m.returnType == "Z" &&
                m.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
                m.implementation?.instructions?.any { insn ->
                    ((insn as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                        ?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)
                        ?.string == "SubscribePro"
                } == true
        }
        assertForcedBoolean(
            proGate,
            expected = true,
            label = "YouCut Pro gate",
            diagnostics = diagnostics,
        )

        // Disable ads: the placement switch returns false.
        val adSwitch = classes.flatMap { it.methods }.single { m ->
            m.definingClass == "Lcom/camerasideas/mobileads/a;" &&
                m.returnType == "Z" &&
                m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
        }
        assertForcedBoolean(adSwitch, expected = false, label = "YouCut ad placement switch", diagnostics = diagnostics)

        // Telemetry: both Analytics emitters and Crashlytics' void API return at once.
        val analyticsEmitters = classes.flatMap { it.methods }.filter { m ->
            m.returnType == "V" &&
                m.implementation?.instructions?.any { insn ->
                    val ref = (insn as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                        ?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference
                    ref?.definingClass == "Lcom/google/android/gms/internal/measurement/zzff;" &&
                        ref.name == "zzy"
                } == true
        }
        assertEquals(
            2,
            analyticsEmitters.size,
            "expected two Analytics emitters, found ${analyticsEmitters.map { "${it.definingClass}->${it.name}" }}",
        )
        analyticsEmitters.forEach { assertReturnsEarlyVoid(it, "analytics emitter ${it.definingClass}->${it.name}") }

        val crashlytics = klass("Lcom/google/firebase/crashlytics/FirebaseCrashlytics;")
        crashlytics.methods
            .filter { it.returnType == "V" && it.name != "<init>" && it.name != "<clinit>" }
            .forEach { assertReturnsEarlyVoid(it, "Crashlytics ${it.name}") }

        // Disable ads library hooks: one banner load and one full-screen load/show each.
        assertReturnsEarlyVoid(
            method("Lcom/applovin/mediation/ads/MaxAdView;", "loadAd"),
            "MAX banner load",
        )
        assertReturnsEarlyVoid(
            method("Lcom/google/android/gms/ads/BaseAdView;", "loadAd"),
            "GMA banner load",
        )
        assertReturnsEarlyVoid(
            method("Lcom/applovin/mediation/ads/MaxInterstitialAd;", "showAd", listOf("Landroid/app/Activity;")),
            "MAX interstitial show",
        )

        // Native signature check: the one SIGKILL call is gone in the emitted APK.
        val patchedApks = workDir.walkTopDown()
            .filter { it.isFile && it.extension == "apk" }
            .filter { candidate ->
                ZipFile(candidate).use { zip -> zip.getEntry(LIB_ARM64) != null }
            }
            .toList()
        assertEquals(1, patchedApks.size, "expected one patched APK with $LIB_ARM64, found ${patchedApks.map { it.path }}")
        val patchedLib = ZipFile(patchedApks.single()).use { zip ->
            zip.getInputStream(zip.getEntry(LIB_ARM64)).use { it.readBytes() }
        }
        assertEquals(stockLib.size, patchedLib.size, "the native library changed size")
        assertTrue(patchedLib.sigkillCalls().isEmpty(), "the SIGKILL call is still present in the patched library")
        assertTrue(
            patchedLib.copyOfRange(killOffset + 4, killOffset + 8).contentEquals(NOP),
            "expected a NOP at the kill call site (offset ${killOffset + 4})",
        )
        assertTrue(
            patchedLib.copyOfRange(0, killOffset).contentEquals(stockLib.copyOfRange(0, killOffset)),
            "bytes before the kill site changed",
        )
        assertTrue(
            patchedLib.copyOfRange(killOffset + 8, patchedLib.size)
                .contentEquals(stockLib.copyOfRange(killOffset + 8, stockLib.size)),
            "bytes after the kill site changed",
        )

        multiAbiBundleIsAccepted(root, apk, stockLib, killOffset)
    }

    /**
     * #190: an APKMirror bundle also carries armeabi-v7a copies. Synthetic input = the arm64 APK
     * plus a dummy v7a library; the patch must not throw, must NOP the arm64 kill site only, and
     * must leave the v7a entry byte-for-byte as it was.
     */
    private fun multiAbiBundleIsAccepted(root: File, apk: File, stockLib: ByteArray, killOffset: Int) {
        val dir = File(workDir, "multiabi").also { it.mkdirs() }
        val multi = File(dir, "multi-abi.apk")
        apk.copyTo(multi, overwrite = true)
        val dummyV7a = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 1, 2, 3, 4, 5, 6, 7, 8)
        FileSystems.newFileSystem(multi.toPath(), null as ClassLoader?).use { fs ->
            val entry = fs.getPath(LIB_V7A)
            Files.createDirectories(entry.parent)
            Files.write(entry, dummyV7a)
        }

        var patchedApk: File? = null
        applyPatches(
            apk = multi,
            workDir = File(dir, "work"),
            pkg = PKG,
            version = VERSION,
            // The harness needs a bytecode patch next to the native one (it asserts patched dex files).
            patchNames = setOf("Disable native signature check", "Disable telemetry"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
            versionCode = VERSION_CODE,
            onPatchedApk = { patchedApk = it },
        )
        val (arm64, v7a) = ZipFile(patchedApk ?: error("no patched APK")).use { zip ->
            fun read(name: String) = zip.getInputStream(zip.getEntry(name) ?: error("$name missing")).use { it.readBytes() }
            read(LIB_ARM64) to read(LIB_V7A)
        }
        assertEquals(stockLib.size, arm64.size, "the arm64 library changed size")
        assertTrue(arm64.sigkillCalls().isEmpty(), "multi-ABI: the arm64 SIGKILL call is still present")
        val expected = stockLib.copyOf().also { NOP.copyInto(it, killOffset + 4) }
        assertTrue(arm64.contentEquals(expected), "multi-ABI: arm64 library differs from stock by more than the NOP")
        assertTrue(v7a.contentEquals(dummyV7a), "multi-ABI: the armeabi-v7a copy was modified")
    }
}
