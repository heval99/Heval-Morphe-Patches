import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.l"

class ListonicSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `disable ads and telemetry change the ad lock, the SDK wrappers and the Firebase switches`() {
        val root = repoRoot()
        val apk = File(root, "apks/listonic/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "9.25.2",
            versionCode = 240092502,
            patchNames = setOf("Disable ads", "Disable telemetry"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        fun ClassDef.hasString(value: String) = methods.any { m ->
            m.instructions().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value }
        }

        fun Method.newInstances(type: String) = instructions().any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == type
        }

        // 1. The lock manager: release (builds LockReleased) returns at once, engage (builds
        // LockEngaged) is untouched, and both sit in the same class.
        val released = classes.single { it.hasString("LockReleased(lockType=") }
        val engaged = classes.single { it.hasString("LockEngaged(lockType=") }
        val releaseMethods = classes.flatMap { c -> c.methods.filter { it.newInstances(released.type) } }
        val engageMethods = classes.flatMap { c -> c.methods.filter { it.newInstances(engaged.type) } }
        assertEquals(1, releaseMethods.size, "exactly one method builds LockReleased")
        assertEquals(1, engageMethods.size, "exactly one method builds LockEngaged")
        val release = releaseMethods.single()
        val engage = engageMethods.single()
        assertEquals(engage.definingClass, release.definingClass, "release and engage live in the lock manager")
        assertEquals(listOf("L"), release.parameterTypes.map { if (it.startsWith("L")) "L" else it }, "release takes the lock")
        assertReturnsEarlyVoid(release, "lock manager release")
        assertTrue(
            engage.instructions().first().opcode != Opcode.RETURN_VOID && engage.instructions().size > 20,
            "engage must stay intact; first is ${engage.instructions().first().opcode}",
        )

        // 2. AdProviderRegistry.initializeEager returns early; getFactory is untouched.
        val registry = classes.single { it.type == "Lcom/listonic/adcompanion/core/provider/AdProviderRegistry;" }
        assertReturnsEarlyVoid(registry.method("initializeEager"), "AdProviderRegistry.initializeEager")
        assertTrue(
            registry.method("getFactory").instructions().first().opcode != Opcode.RETURN_OBJECT,
            "getFactory must stay intact",
        )

        // 3. AppConfiguration(theme, ..., premiumActive, isAdsEnabled): the last boolean starts as 0.
        val config = classes.single { it.hasString("AppConfiguration(theme=") }
        val ctor = config.methods.single { it.name == "<init>" && it.parameterTypes.map { p -> p.toString() }.let { p -> p.size == 5 && p[3] == "Z" && p[4] == "Z" } }
        val first = ctor.instructions().first()
        assertEquals(Opcode.CONST_4, first.opcode, "AppConfiguration constructor must start with const/4")
        assertEquals(
            ctor.implementation!!.registerCount - 1,
            (first as OneRegisterInstruction).registerA,
            "const/4 must write the last parameter (isAdsEnabled)",
        )
        assertEquals(0, (first as NarrowLiteralInstruction).narrowLiteral, "isAdsEnabled must start false")

        // ---- Disable telemetry ----
        fun klass(type: String) = classes.firstOrNull { it.type == type } ?: error("$type not found in emitted dexes")
        fun first(method: Method) = method.instructions().first().opcode

        // 1. Firebase: Crashlytics arbiter getter false, Sessions override FALSE, analytics
        // deactivated read forced TRUE, adid read forced FALSE.
        val arbiter = klass("Lcom/google/firebase/crashlytics/internal/common/DataCollectionArbiter;")
        val getter = arbiter.methods.single {
            AccessFlags.DECLARED_SYNCHRONIZED.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.isEmpty()
        }
        assertForcedBoolean(getter, expected = false, label = "Crashlytics isAutomaticDataCollectionEnabled")
        assertEquals(
            Opcode.SGET_OBJECT,
            first(klass("Lcom/google/firebase/sessions/settings/LocalOverrideSettings;").method("getSessionEnabled")),
            "Sessions override must start with Boolean.FALSE",
        )
        fun forcedBooleanReads(method: Method, field: String) = method.instructions().count {
            val ref = (it as? ReferenceInstruction)?.reference
            it.opcode == Opcode.SGET_OBJECT && ref is FieldReference && ref.definingClass == "Ljava/lang/Boolean;" && ref.name == field
        }
        val measurementConfig = klass("Lcom/google/android/gms/measurement/internal/zzam;")
        assertTrue(forcedBooleanReads(measurementConfig.method("zzA"), "TRUE") >= 1, "analytics deactivated read must be forced TRUE")
        assertTrue(forcedBooleanReads(measurementConfig.method("zzw"), "FALSE") >= 1, "analytics adid read must be forced FALSE")

        // 2. TikTok wrapper.
        val tiktok = klass("Lcom/l/utils/ads/TikTokReporterImpl;")
        for (name in listOf("start", "beginTracking", "track", "setConsent")) {
            val m = tiktok.methods.single { it.name == name }
            assertReturnsEarlyVoid(m, "TikTokReporterImpl.$name")
        }

        // 3. AdShift wrapper: all four return at once, the device id is null.
        val adshift = klass("Lcom/l/utils/ads/AdShiftManager;")
        for (name in listOf("initialize", "start", "stop", "handleAppLinkIntent")) {
            assertReturnsEarlyVoid(adshift.methods.single { it.name == name }, "AdShiftManager.$name")
        }
        assertEquals(Opcode.CONST_4, first(adshift.method("getAdShiftDeviceId")), "AdShift device id must start by loading null")

        // 4. Clarity library statics.
        val clarity = klass("Lcom/microsoft/clarity/Clarity;")
        for (m in clarity.methods.filter { it.name == "initialize" }) {
            assertEquals(2, clarity.methods.count { it.name == "initialize" }, "both Clarity.initialize overloads")
            assertEquals(Opcode.SGET_OBJECT, first(m), "Clarity.initialize must start with Boolean.FALSE")
        }
        assertForcedBoolean(clarity.method("sendCustomEvent"), expected = false, label = "Clarity.sendCustomEvent")
        assertEquals(Opcode.SGET_OBJECT, first(clarity.method("setCurrentScreenName")), "Clarity.setCurrentScreenName")

        // 5. Gemius.
        assertReturnsEarlyVoid(klass("Lcom/gemius/sdk/GemiusSdk;").method("init"), "GemiusSdk.init")
        assertReturnsEarlyVoid(klass("Lcom/gemius/sdk/audience/AudienceEvent;").method("sendEvent"), "AudienceEvent.sendEvent")

        // 6. Facebook SDK flags.
        val facebook = klass("Lcom/facebook/FacebookSdk;")
        assertForcedBoolean(facebook.method("getAutoLogAppEventsEnabled"), expected = false, label = "FacebookSdk.getAutoLogAppEventsEnabled")
        assertForcedBoolean(facebook.method("getAdvertiserIDCollectionEnabled"), expected = false, label = "FacebookSdk.getAdvertiserIDCollectionEnabled")
    }
}
