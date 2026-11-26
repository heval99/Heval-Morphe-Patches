import app.morphe.patcher.patch.PatchException
import app.template.patches.outfit7.cheats.FREE_PURCHASE_SITES
import app.template.patches.outfit7.cheats.LIBIL2CPP_ARM64
import app.template.patches.outfit7.cheats.NativeSite
import app.template.patches.outfit7.cheats.PET_METERS_SITE
import app.template.patches.outfit7.cheats.applyNativeSites
import app.template.patches.outfit7.cheats.findSite
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class MyTalkingTomSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable ads and the cheats patch the inventory binding, the splash loader and libil2cpp`() {
        val root = repoRoot()
        val apk = File(root, "apks/mytalkingtom/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        assertNativeSiteHelperFailsLoudly()

        val stockLib = ZipFile(apk).use { zip ->
            val entry = zip.getEntry(LIBIL2CPP_ARM64) ?: error("$LIBIL2CPP_ARM64 missing from ${apk.path}")
            zip.getInputStream(entry).use { it.readBytes() }
        }

        val allPatches = loadAllPatches(newestPatchBundle(root))
        // Both cheats are opt-in.
        for (name in listOf("Free purchases", "Freeze pet meters")) {
            val patch = allPatches.single { it.name == name }
            assertFalse(patch.default, "'$name' must be off by default")
            assertEquals(
                listOf("com.outfit7.mytalkingtomfree"),
                patch.compatibility.orEmpty().map { it.packageName },
                "'$name' must be compatible with My Talking Tom only",
            )
        }

        var patchedApk: File? = null
        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = "com.outfit7.mytalkingtomfree",
            version = "26.5.4.9664",
            patchNames = setOf("Disable ads", "Free purchases", "Freeze pet meters"),
            allPatches = allPatches,
            onPatchedApk = { patchedApk = it },
        )
        assertOutfit7AdsDisabled(classes)

        // Each window matches exactly once in the stock library, at the documented file offsets.
        val debit = findSite(stockLib, FREE_PURCHASE_SITES[0])
        val canDebit = findSite(stockLib, FREE_PURCHASE_SITES[1])
        val meters = findSite(stockLib, PET_METERS_SITE)
        assertEquals(listOf(0x21eb45c), debit, "Debit window hits")
        assertEquals(listOf(0x21eb478), canDebit, "CanDebit window hits")
        assertEquals(listOf(0x23b8cf8), meters, "RecalculateMeters window hits")

        val patchedLib = ZipFile(patchedApk ?: error("no patched apk")).use { zip ->
            val entry = zip.getEntry(LIBIL2CPP_ARM64) ?: error("$LIBIL2CPP_ARM64 missing from the patched apk")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        assertEquals(stockLib.size, patchedLib.size, "the native library changed size")

        // The whole library equals stock except for exactly these bytes.
        val expected = stockLib.copyOf()
        fun put(at: Int, hex: String) {
            val b = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            b.copyInto(expected, at)
        }
        put(0x21eb45c, "c0035fd6") // Debit: ret
        put(0x21eb478, "05000014") // CanDebit: b +0x14 (always "can pay")
        put(0x23b8cf8, "e0031f2ac0035fd6") // RecalculateMeters: mov w0, wzr; ret
        val differing = stockLib.indices.filter { stockLib[it] != patchedLib[it] }
        assertTrue(differing.size in 1..16, "unexpected number of differing bytes: ${differing.size}")
        assertTrue(
            differing.all { it in 0x21eb45c until 0x21eb460 || it in 0x21eb478 until 0x21eb47c || it in 0x23b8cf8 until 0x23b8d00 },
            "bytes differ outside the three patch sites: ${differing.take(8).map { it.toString(16) }}",
        )
        assertArrayEquals(expected, patchedLib, "libil2cpp.so differs from the expected patched bytes")
    }

    /** Synthetic library: wrong length, zero hits, two hits and a hit at the wrong offset throw and leave the input alone. */
    private fun assertNativeSiteHelperFailsLoudly() {
        fun site(windowOffset: Int, window: ByteArray = byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0), patchAt: Int = 4) = NativeSite(
            label = "synthetic",
            window = window,
            mask = ByteArray(window.size) { if (it < 4) -1 else 0 }, // the second word is wild
            patchAt = patchAt,
            replacement = byteArrayOf(9, 9, 9, 9),
            windowOffset = windowOffset,
        )

        val word = byteArrayOf(1, 2, 3, 4, 7, 0, 0, 0)
        val one = ByteArray(32).also { word.copyInto(it, 8) }
        val original = one.copyOf()
        val none = ByteArray(32)
        val two = one.copyOf().also { word.copyInto(it, 20) }

        val (patched, offsets) = applyNativeSites(one, listOf(site(8)), expectedLength = 32)
        assertEquals(listOf(12), offsets)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), patched.copyOfRange(12, 16))
        assertArrayEquals(original, one, "input was mutated")

        assertThrows(PatchException::class.java) { applyNativeSites(one, listOf(site(8)), expectedLength = 31) }
        assertThrows(PatchException::class.java) { applyNativeSites(none, listOf(site(8)), expectedLength = 32) }
        assertThrows(PatchException::class.java) { applyNativeSites(two, listOf(site(8)), expectedLength = 32) }
        // Exactly one hit, but not where the documented build has it.
        assertThrows(PatchException::class.java) { applyNativeSites(one, listOf(site(16)), expectedLength = 32) }
        // The real library length is enforced by default.
        assertThrows(PatchException::class.java) { applyNativeSites(one, listOf(site(8))) }
        // All-or-nothing: the second site hits at the wrong offset, so the first is not applied either.
        val withSecond = one.copyOf().also { byteArrayOf(5, 5, 5, 5, 0, 0, 0, 0).copyInto(it, 24) }
        val second = site(20, window = byteArrayOf(5, 5, 5, 5, 0, 0, 0, 0), patchAt = 0)
        assertThrows(PatchException::class.java) { applyNativeSites(withSecond, listOf(site(8), second), expectedLength = 32) }
        assertArrayEquals(original.copyOf().also { byteArrayOf(5, 5, 5, 5).copyInto(it, 24) }, withSecond, "input was mutated after a failed run")
    }
}