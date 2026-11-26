import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.astrapaging.vff"
private const val ADVERT_PLUGIN = "Lcom/izosa/advert/AdvertPlugin;"
private const val PLUGIN_CALL = "Lcom/getcapacitor/PluginCall;"
private const val MOBILE_ADS = "Lcom/google/android/gms/ads/MobileAds;"

class VesselFinderSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `license check, Advert plugin handlers and GMA are patched`() {
        val root = repoRoot()
        val apk = File(root, "apks/vff/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk, workDir = workDir, pkg = PKG, version = "6.8.0",
            patchNames = setOf("Disable license check", "Disable ads"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        fun cls(type: String) = classes.firstOrNull { it.type == type } ?: error("$type not found in emitted dexes")

        // Disable license check: PairIP.
        assertReturnsEarlyVoid(
            cls("Lcom/pairip/licensecheck/LicenseClient;").method("checkLicense", listOf("Landroid/content/Context;")),
            label = "LicenseClient.checkLicense(Context)",
        )

        // Disable ads, step 1: every @PluginMethod handler(PluginCall) on the Advert plugin starts
        // with call.resolve() on its PluginCall parameter, then return-void. Found like the patch.
        val plugin = cls(ADVERT_PLUGIN)
        val handlers = plugin.methods.filter { m ->
            m.returnType == "V" && m.implementation != null &&
                m.parameterTypes.map(CharSequence::toString) == listOf(PLUGIN_CALL) &&
                m.annotations.any { it.type == "Lcom/getcapacitor/PluginMethod;" }
        }
        val names = handlers.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("show", "hide", "resize", "fullscreen")), "Advert handlers found: $names")
        for (handler in handlers) {
            val insns = handler.instructions()
            val label = "AdvertPlugin.${handler.name}(PluginCall)"
            val resolve = insns.getOrNull(0)
            val ref = (resolve as? ReferenceInstruction)?.reference as? MethodReference
            // p1 (the PluginCall) is the last register of the frame.
            val callRegister = handler.implementation!!.registerCount - 1
            assertTrue(
                resolve?.opcode == Opcode.INVOKE_VIRTUAL && ref?.definingClass == PLUGIN_CALL &&
                    ref.name == "resolve" && ref.parameterTypes.isEmpty() &&
                    (resolve as FiveRegisterInstruction).registerC == callRegister,
                "$label does not resolve its call first; found ${resolve?.opcode} $ref",
            )
            assertTrue(
                insns.getOrNull(1)?.opcode == Opcode.RETURN_VOID,
                "$label does not return after resolving; found ${insns.getOrNull(1)?.opcode}",
            )
        }

        // Step 2: the plugin method that calls MobileAds.initialize (initializeMobileAdsSdk)
        // returns at once.
        val initializers = plugin.methods.filter { m ->
            m.instructions().any {
                val r = (it as? ReferenceInstruction)?.reference as? MethodReference
                r?.definingClass == MOBILE_ADS && r.name == "initialize"
            }
        }
        assertTrue(initializers.isNotEmpty(), "no AdvertPlugin method calls MobileAds.initialize")
        initializers.forEach { assertReturnsEarlyVoid(it, label = "AdvertPlugin.${it.name}") }

        // Step 3: rule 9, every MobileAds static void is a no-op and first-party id answers false.
        assertMobileAdsStaticsNeutralised(cls(MOBILE_ADS))
    }
}
