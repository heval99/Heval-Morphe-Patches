import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "bbc.mobile.news.ww"
private const val AD_MANAGER_AD_VIEW = "Lcom/google/android/gms/ads/admanager/AdManagerAdView;"
private const val FULL_SCREEN = "Lio/invertase/googlemobileads/ReactNativeGoogleMobileAdsFullScreenAdModule;"
private const val NATIVE = "Lio/invertase/googlemobileads/ReactNativeGoogleMobileAdsNativeModule;"

class BbcNewsSmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun List<ClassDef>.type(type: String): ClassDef =
        firstOrNull { it.type == type } ?: error("$type not found in emitted dexes")

    private fun Method.calls(): List<MethodReference> = instructions()
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }

    @Test
    fun `Disable ads and Spoof signature change the real code`() {
        val root = repoRoot()
        val apk = File(root, "apks/bbcnews/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "2026.11.0",
            patchNames = setOf("Disable ads", "Spoof signature"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val diagnostics = "emitted classes=${classes.size}"

        // 1 and 2. Banners: every concrete void loadAd on AdManagerAdView and on its superclass
        // (BaseAdView, renamed by R8), found the way the patch does.
        val adManager = classes.type(AD_MANAGER_AD_VIEW)
        val baseAdView = classes.type(adManager.superclass!!)
        for (cls in listOf(baseAdView, adManager)) {
            val loads = cls.methods.filter { it.name == "loadAd" && it.returnType == "V" && it.implementation != null }
            assertTrue(loads.isNotEmpty(), "${cls.type} has no concrete void loadAd")
            loads.forEach { assertReturnsEarlyVoid(it, "${cls.type}->loadAd", diagnostics) }
        }

        // 3. Full-screen load reports an error event (sendAdEvent) with a no-fill map, then returns.
        val fullScreen = classes.type(FULL_SCREEN)
        val load = fullScreen.methods.single {
            it.name == "load" && it.parameterTypes.map(CharSequence::toString) ==
                listOf("I", "Ljava/lang/String;", "Lcom/facebook/react/bridge/ReadableMap;")
        }
        val loadCalls = load.calls()
        assertEquals(
            "Lcom/facebook/react/bridge/Arguments;",
            loadCalls.firstOrNull()?.definingClass,
            "full-screen load must start by building the error map; $diagnostics",
        )
        assertTrue(
            loadCalls.any { it.definingClass == FULL_SCREEN && it.parameterTypes.size == 5 && it.returnType == "V" },
            "full-screen load must call sendAdEvent",
        )
        assertEquals(
            Opcode.RETURN_VOID,
            load.instructions().first { it.opcode == Opcode.RETURN_VOID }.opcode,
        )
        // The first return-void comes right after the sendAdEvent call: the stock body is dead code.
        val loadOps = load.instructions().map { it.opcode }
        assertEquals(
            Opcode.INVOKE_DIRECT_RANGE,
            loadOps[loadOps.indexOf(Opcode.RETURN_VOID) - 1],
            "full-screen load must return straight after sendAdEvent",
        )

        // 4. Native load rejects the promise with a no-fill code and returns.
        val nativeLoad = classes.type(NATIVE).methods.single {
            it.name == "load" && it.parameterTypes.map(CharSequence::toString) == listOf(
                "Ljava/lang/String;", "Lcom/facebook/react/bridge/ReadableMap;", "Lcom/facebook/react/bridge/Promise;",
            )
        }
        val nativeCall = nativeLoad.calls().first()
        assertEquals("Lcom/facebook/react/bridge/Promise;", nativeCall.definingClass)
        assertEquals("reject", nativeCall.name)
        val nativeOps = nativeLoad.instructions().map { it.opcode }
        assertEquals(
            Opcode.RETURN_VOID,
            nativeOps[nativeOps.indexOf(Opcode.INVOKE_INTERFACE) + 1],
            "native load must return straight after reject",
        )

        // Spoof signature: the extension carries the package and the BBC Worldwide certificate
        // (SHA-1 from META-INF/BNDLTOOL.RSA), and no direct getPackageInfo(String, ...) call is left.
        assertSpoofSignature(classes, PKG, "25a95fddc952150c80b9c33ab8d17d8e8e139f40")
    }
}
