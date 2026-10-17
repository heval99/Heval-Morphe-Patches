import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.scores365"
private const val ENTITLEMENT_STATE = "Lcom/scores365/billingClient/entitlement/EntitlementState;"
private const val BASE_ACTIVITY = "Lcom/scores365/Design/Activities/BaseActivity;"
private const val BASE_ACTION_BAR_ACTIVITY = "Lcom/scores365/Design/Activities/BaseActionBarActivity;"
private const val SPLASH = "Lcom/scores365/ui/Splash;"
private const val SIGNATURE_SHA1 = "47f994bd2eac89f4aeea0aa1d95cb3ba3ec3f1a1"

class Scores365SmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `365Scores patches unlock premium, remove ads and update prompts, and route GMS to MicroG RE`() {
        val root = repoRoot()
        val apk = File(root, "apks/365scores/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "14.9.7",
            patchNames = setOf(
                "Disable ads", "Enable Premium", "Disable update prompts", "GmsCore support", "Spoof signature",
            ),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val byType = classes.associateBy { it.type }

        assertDisableAds(classes, byType)
        assertEnablePremium(byType)
        assertDisableUpdatePrompts(byType)
        assertGmsCoreSupport(classes, workDir, PKG, SPLASH, SIGNATURE_SHA1)
        assertSpoofSignature(classes, PKG, SIGNATURE_SHA1)
    }

    private fun assertDisableAds(classes: List<ClassDef>, byType: Map<String, ClassDef>) {
        val mobileAds = byType["Lcom/google/android/gms/ads/MobileAds;"]
            ?: error("MobileAds not found in emitted dexes")
        assertReturnsEarlyVoid(
            mobileAds.method("initialize", listOf("Landroid/content/Context;")),
            label = "MobileAds.initialize(Context)",
        )
        assertReturnsEarlyVoid(
            mobileAds.method(
                "initialize",
                listOf(
                    "Landroid/content/Context;",
                    "Lcom/google/android/gms/ads/initialization/OnInitializationCompleteListener;",
                ),
            ),
            label = "MobileAds.initialize(Context, Listener)",
        )
        // Rule 9: no MobileAds static can throw for lack of init.
        assertMobileAdsStaticsNeutralised(mobileAds)

        // Every concrete app ad gate returns false, including the readable base activities.
        val gates = classes.flatMap { cls ->
            cls.methods.filter {
                it.name in setOf("showAdsForContext", "isBannerNeedToBeShown") &&
                    it.returnType == "Z" && it.parameterTypes.isEmpty() && it.implementation != null
            }.map { cls to it }
        }
        for (type in listOf(
            BASE_ACTION_BAR_ACTIVITY,
            "Lcom/scores365/Design/Activities/BottomNavigationActivity;",
            "Lcom/scores365/gameCenter/GameCenterBaseActivity;",
            "Lcom/scores365/ui/WebViewActivity;",
        )) {
            check(gates.any { (cls, _) -> cls.type == type }) { "no app ad gate patched in $type" }
        }
        for ((cls, method) in gates) {
            assertForcedBoolean(method, expected = false, label = "${cls.type}.${method.name}()")
        }
    }

    private fun assertEnablePremium(byType: Map<String, ClassDef>) {
        // The constructor overwrites the five feature flags (p1..p5) with true before storing them.
        val state = byType[ENTITLEMENT_STATE] ?: error("$ENTITLEMENT_STATE not found")
        val constructor = state.method(
            "<init>",
            listOf("Z", "Z", "Z", "Z", "Z", "Ljava/util/Set;", "Ljava/util/List;", "J", "Z"),
        )
        // p0 = this; parameters take 1+1+1+1+1+1+1+2+1 = 10 registers.
        val p1 = constructor.implementation!!.registerCount - 10
        val insns = constructor.instructions()
        for (slot in 0 until 5) {
            val insn = insns.getOrNull(slot)
            check(
                insn?.opcode == Opcode.CONST_16 &&
                    (insn as OneRegisterInstruction).registerA == p1 + slot &&
                    (insn as WideLiteralInstruction).wideLiteral == 1L
            ) {
                "EntitlementState.<init> instruction $slot is not const/16 p${slot + 1}, 0x1; got ${insn?.opcode}"
            }
        }

        // The Gson-restored cache skips the constructor, so the getters are forced too.
        for (getter in listOf(
            "isPremium", "getAdsFree", "getTipster", "getPlus", "getDelayedNotifications", "getNotificationSounds",
        )) {
            assertForcedBoolean(state.method(getter, emptyList()), expected = true, label = "EntitlementState.$getter()")
        }

        // The Remove ads screen no longer sees the grace start time the forced state triggers.
        val removeAds = byType["Lcom/scores365/removeAds/RemoveAdsBasicActivity;"]
            ?: error("RemoveAdsBasicActivity not found")
        val strings = removeAds.method("onCreate", listOf("Landroid/os/Bundle;")).instructions().mapNotNull { it.stringOrNull() }
        check("iabc_fnrts" !in strings) { "RemoveAdsBasicActivity.onCreate still reads \"iabc_fnrts\"" }
        check("morphe_disabled_iabc_fnrts" in strings) { "RemoveAdsBasicActivity.onCreate is missing the renamed key" }
    }

    private fun assertDisableUpdatePrompts(byType: Map<String, ClassDef>) {
        for (type in listOf(BASE_ACTIVITY, BASE_ACTION_BAR_ACTIVITY)) {
            val onCreate = (byType[type] ?: error("$type not found")).method("onCreate", listOf("Landroid/os/Bundle;"))
            val strings = onCreate.instructions().mapNotNull { it.stringOrNull() }
            for (key in listOf("ForceUpdateDialog", "NewVersionPopup")) {
                check(key !in strings) { "$type.onCreate still reads the \"$key\" extra" }
                check("morphe_disabled_$key" in strings) { "$type.onCreate is missing the renamed \"$key\" key" }
            }
        }
    }
}
