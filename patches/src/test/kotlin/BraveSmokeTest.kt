import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.brave.browser"
private const val PREFS = "Lorg/chromium/chrome/browser/settings/BraveOriginPreferences;"

class BraveSmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun Instruction.referencesString(value: String): Boolean {
        val ref = (this as? ReferenceInstruction)?.reference
        return ref is StringReference && ref.string == value
    }

    private fun Instruction.referencesMethod(owner: String, name: String): Boolean {
        val ref = (this as? ReferenceInstruction)?.reference as? MethodReference
        return ref?.definingClass == owner && ref.name == name
    }

    @Test
    fun `Brave Origin forces the subscription predicates and neuters the writers`() {
        val root = repoRoot()
        val apk = File(root, "apks/brave/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "1.96.61",
            patchNames = setOf("Brave Origin"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        val allMethods: List<Method> = classes.flatMap { it.methods }

        fun methodWithString(value: String): Method = allMethods.firstOrNull { method ->
            method.implementation?.instructions?.any { it.referencesString(value) } == true
        } ?: error("no method references string '$value'")

        fun methodWithStringPrefix(prefix: String): Method = allMethods.firstOrNull { method ->
            method.instructions().any { insn ->
                val ref = (insn as? ReferenceInstruction)?.reference
                ref is StringReference && ref.string.startsWith(prefix)
            }
        } ?: error("no method references a string starting with '$prefix'")

        // The credentials/subscription predicates are forced true.
        assertForcedBoolean(
            methodWithStringPrefix("getIsSubscriptionActive"),
            expected = true,
            label = "isSubscriptionActive(Profile)",
        )
        assertForcedBoolean(
            methodWithString("brave.origin.order_id_android"),
            expected = true,
            label = "hasValidSubscriptionTokens(Profile)",
        )
        assertForcedBoolean(
            methodWithString("brave_origin_credential_summary_cached"),
            expected = true,
            label = "credential summary cached",
        )

        // The credential summary request answers TRUE before reaching the SKUs service.
        val summaryRequest = methodWithString("SkusService is null, cannot request credential summary")
        val summaryHead = summaryRequest.instructions().take(3)
        assertTrue(
            summaryHead.map { it.opcode } ==
                listOf(Opcode.SGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.RETURN_VOID) &&
                ((summaryHead[0] as ReferenceInstruction).reference as FieldReference).name == "TRUE" &&
                summaryHead[1].referencesMethod("Lorg/chromium/base/Callback;", "onResult"),
            "requestCredentialSummary does not fire Boolean.TRUE immediately; head is " +
                summaryHead.map { it.opcode },
        )

        // The package/product writer is a no-op.
        val writer = allMethods.firstOrNull { method ->
            val strings = method.instructions()
                .filter { it.referencesString("brave.origin.package_name_android") }
            strings.isNotEmpty() &&
                method.instructions().any { it.referencesString("brave.origin.product_id_android") }
        } ?: error("package/product writer not found")
        assertReturnsEarlyVoid(writer, label = "Origin package/product writer")

        // The restart prompt launcher is a no-op.
        val launcher = classes.firstOrNull {
            it.type == "Lorg/chromium/chrome/browser/brave_origin/BraveOriginSettingsLauncherHelper;"
        } ?: error("BraveOriginSettingsLauncherHelper not found")
        assertReturnsEarlyVoid(
            launcher.method("showOriginSettingsForRestart"),
            label = "showOriginSettingsForRestart()",
        )

        // Gatekeepers now read SharedPreferences instead of the native policy service. The
        // patch replaces the whole body, so the old policy string is gone; find the rewritten
        // method by the pref key it now uses.
        val rewardsGate = allMethods.firstOrNull { method ->
            method.instructions().any { it.referencesString("brave_origin_off_BraveRewardsDisabled") }
        } ?: error("rewards gatekeeper with pref key not found")
        assertTrue(
            rewardsGate.instructions().any {
                it.referencesMethod("Landroid/preference/PreferenceManager;", "getDefaultSharedPreferences")
            },
            "rewards gatekeeper was not rewritten to read SharedPreferences",
        )

        // Preference listener: the managed switch binder stores state and returns.
        val prefs = classes.firstOrNull { it.type == PREFS }
            ?: error("BraveOriginPreferences not found")
        val binder = prefs.methods.firstOrNull { method ->
            method.instructions().any {
                it.referencesMethod("Landroid/preference/PreferenceManager;", "getDefaultSharedPreferences")
            }
        }
        assertTrue(binder != null, "BraveOriginPreferences listener was not rewritten")
    }
}
