import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.sofascore.results"
private const val USER_ACCOUNT = "Lcom/sofascore/local_persistance/UserAccount;"
private const val PROFILE_DATA = "Lcom/sofascore/model/profile/ProfileData;"
private const val APPSFLYER_LIB = "Lcom/appsflyer/AppsFlyerLib;"
private const val GMS_MEASUREMENT = "Lcom/google/android/gms/measurement/api/AppMeasurementSdk;"
private const val FACEBOOK_PROVIDER = "Lcom/facebook/internal/FacebookInitProvider;"
private const val AUDIENCE_PROVIDER = "Lcom/facebook/ads/AudienceNetworkContentProvider;"
private const val PROMOTION_MODAL = "Lcom/sofascore/results/event/details/view/promotion/PromotionModal;"
private const val TENNIS_PROMO_SHEET = "Lcom/sofascore/results/event/aiInsights/SofascoreAnalystTennisPromoBottomSheet;"
private const val CRASHLYTICS_KEY = "firebase_crashlytics_collection_enabled"
private val PROMO_BANNERS = listOf(
    "Lcom/sofascore/results/event/details/view/promotion/PromotionBannerView;",
    "Lcom/sofascore/results/featuredtournament/view/PromotionalOffersBannerView;",
)

private const val CONTEXT = "Landroid/content/Context;"
private const val SHARED_PREFERENCES = "Landroid/content/SharedPreferences;"
private const val EVENT_HEAD_FLAGS = "Lcom/sofascore/model/mvvm/model/EventHeadFlags;"
private const val BET365_SIGN_UP_PREFIX = "https://www.bet365.com/olp/open-account?affiliate="

private const val START_ACTIVITY = "Lcom/sofascore/results/main/start/StartActivity;"
private const val SIGNATURE_SHA1 = "e235776bc0ad3837b01359177c438658b1424d23"

private val SOFASCORE_PATCHES = setOf(
    "Block marketing notifications",
    "Disable Facebook SDK",
    "Disable ads",
    "Disable rating prompts",
    "Disable telemetry",
    "Enable Premium",
    "GmsCore support",
    "Hide betting content",
    "Spoof signature",
)

class SofascoreSmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun ClassDef.methodsNamed(name: String): List<Method> = methods.filter { it.name == name }

    private fun ClassDef.referencesString(value: String): Boolean = methods.any { method ->
        method.instructions().any { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference
            ref is StringReference && ref.string == value
        }
    }

    private fun assertForcedBoxedTrue(method: Method, label: String, diagnostics: String) {
        val insns = method.instructions()
        val first = insns.getOrNull(0) as? ReferenceInstruction
        val ref = first?.reference as? FieldReference
        assertTrue(
            first != null &&
                first.opcode == Opcode.SGET_OBJECT &&
                ref != null &&
                ref.definingClass == "Ljava/lang/Boolean;" &&
                ref.name == "TRUE",
            "$label was not forced to Boolean.TRUE; first instruction is ${insns.getOrNull(0)?.opcode}\n$diagnostics"
        )
        assertTrue(
            insns.getOrNull(1)?.opcode == Opcode.RETURN_OBJECT,
            "$label does not return immediately; second instruction is ${insns.getOrNull(1)?.opcode}\n$diagnostics"
        )
    }

    private fun assertDismissesEarly(method: Method, label: String, diagnostics: String) {
        val insns = method.instructions()
        val first = insns.getOrNull(0) as? ReferenceInstruction
        val ref = first?.reference as? MethodReference
        assertTrue(
            ref != null &&
                ref.definingClass == "Landroidx/fragment/app/DialogFragment;" &&
                ref.name == "dismiss",
            "$label does not dismiss the dialog first; first instruction is ${first?.opcode} ${ref}\n$diagnostics"
        )
        assertTrue(
            insns.getOrNull(1)?.opcode == Opcode.RETURN_VOID,
            "$label does not return after dismissing; second instruction is ${insns.getOrNull(1)?.opcode}\n$diagnostics"
        )
    }

    private fun Method.strings(): List<String> =
        instructions().mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }

    private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

    private fun Method.params() = parameterTypes.map(CharSequence::toString)

    /** The modal-queue predicate, found like sofascore/shared/ModalQueue.kt finds it. */
    private fun modalPredicate(classes: List<ClassDef>, modalName: String, anchor: String, diagnostics: String): Method {
        val byType = classes.associateBy { it.type }
        val descriptor = classes.filter { cls ->
            cls.methods.any { it.name == "<clinit>" && modalName in it.strings() }
        }.singleOrNull() ?: error("no single modal descriptor named \"$modalName\"\n$diagnostics")
        val created = descriptor.methods.single { it.name == "<clinit>" }.instructions()
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        return created.mapNotNull { byType[it] }
            .filter { "Lkotlin/jvm/functions/Function1;" in it.interfaces }
            .mapNotNull { cls -> cls.methods.firstOrNull { it.name == "invoke" && anchor in it.strings() } }
            .singleOrNull() ?: error("no single \"$modalName\" predicate holding \"$anchor\"\n$diagnostics")
    }

    /** The call [call] selects (exactly one) is followed by move-result vX, then const vX, [expected]. */
    private fun assertCallResultForced(
        method: Method,
        expected: Boolean,
        label: String,
        diagnostics: String,
        call: (MethodReference, Int) -> Boolean,
    ) {
        val insns = method.instructions()
        val index = insns.indices.singleOrNull { i ->
            val ref = (insns[i] as? ReferenceInstruction)?.reference as? MethodReference
            ref != null && call(ref, i) // only invoke-* carry a MethodReference
        } ?: error("$label: call not found exactly once\n$diagnostics")
        val moveResult = insns.getOrNull(index + 1) as? OneRegisterInstruction
        val forced = insns.getOrNull(index + 2)
        assertTrue(
            insns.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT && moveResult != null &&
                forced is NarrowLiteralInstruction && forced is OneRegisterInstruction &&
                (forced.opcode == Opcode.CONST_4 || forced.opcode == Opcode.CONST_16) &&
                forced.registerA == moveResult.registerA &&
                forced.narrowLiteral == if (expected) 1 else 0,
            "$label is not forced to $expected after its move-result; got ${forced?.opcode}\n$diagnostics"
        )
    }

    @Test
    fun `all Sofascore patches apply and mutate the expected bytecode`() {
        val root = repoRoot()
        val apk = File(root, "apks/sofascore/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "26.10.05",
            patchNames = SOFASCORE_PATCHES,
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val byType = classes.associateBy { it.type }
        val diagnostics = "emitted classes=${classes.size}"

        fun require(type: String): ClassDef =
            byType[type] ?: error("$type not found in emitted dexes\n$diagnostics")

        // Disable ads: the three UserAccount flags the ad logic consults.
        assertForcedBoolean(
            require(USER_ACCOUNT).methodsNamed("getForceAds").single(),
            expected = false, label = "UserAccount.getForceAds()", diagnostics
        )
        assertForcedBoolean(
            require(USER_ACCOUNT).methodsNamed("getForceHideAds").single(),
            expected = true, label = "UserAccount.getForceHideAds()", diagnostics
        )
        assertForcedBoolean(
            require(USER_ACCOUNT).methodsNamed("getHasServerAds").single(),
            expected = false, label = "UserAccount.getHasServerAds()", diagnostics
        )

        // Enable Premium: both boxed-Boolean premium flags.
        assertForcedBoxedTrue(
            require(USER_ACCOUNT).methodsNamed("getHasPremium").single(),
            label = "UserAccount.getHasPremium()", diagnostics
        )
        assertForcedBoxedTrue(
            require(PROFILE_DATA).methodsNamed("getHasPremium").single(),
            label = "ProfileData.getHasPremium()", diagnostics
        )

        // Disable telemetry: AppsFlyer implementation + GMS measurement.
        val appsFlyer = classes.firstOrNull { it.superclass == APPSFLYER_LIB }
            ?: error("AppsFlyerLib subclass not found in emitted dexes\n$diagnostics")
        val appsFlyerLogEvent = appsFlyer.methodsNamed("logEvent")
            .single { it.parameterTypes.size == 4 }
        assertReturnsEarlyVoid(
            appsFlyerLogEvent,
            label = "${appsFlyer.type}.logEvent(Context, String, Map, listener)",
            diagnostics
        )
        assertReturnsEarlyVoid(
            require(GMS_MEASUREMENT).methodsNamed("logEvent").single(),
            label = "AppMeasurementSdk.logEvent()", diagnostics
        )

        // Crashlytics is R8-renamed; locate its classes the same way the patch does.
        val crashlyticsClasses = classes.filter { it.referencesString(CRASHLYTICS_KEY) }
        assertTrue(
            crashlyticsClasses.isNotEmpty(),
            "no class referencing '$CRASHLYTICS_KEY' in emitted dexes\n$diagnostics"
        )
        val crashlyticsSettings = crashlyticsClasses.firstNotNullOfOrNull { cls ->
            cls.methods.firstOrNull { it.returnType == "Z" && it.parameterTypes.isEmpty() }
        } ?: error("Crashlytics settings getter not found\n$diagnostics")
        assertForcedBoolean(
            crashlyticsSettings, expected = false,
            label = "Crashlytics collection-enabled getter", diagnostics
        )
        val crashlyticsRecordException = crashlyticsClasses.firstNotNullOfOrNull { cls ->
            cls.methods.firstOrNull {
                it.returnType == "V" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes.first().toString() == "Ljava/lang/Throwable;"
            }
        } ?: error("Crashlytics recordException not found\n$diagnostics")
        assertReturnsEarlyVoid(
            crashlyticsRecordException,
            label = "Crashlytics recordException(Throwable)", diagnostics
        )

        // Disable Facebook SDK: only the Audience Network (ads) init provider is
        // neutered. FacebookInitProvider must keep initializing the SDK, otherwise
        // the login screen crashes with "SDK has not been initialized" (issue #24).
        assertForcedBoolean(
            require(AUDIENCE_PROVIDER).methodsNamed("onCreate").single(),
            expected = false, label = "AudienceNetworkContentProvider.onCreate()", diagnostics
        )
        val initOnCreate = require(FACEBOOK_PROVIDER).methodsNamed("onCreate").single()
        assertTrue(
            initOnCreate.instructions().firstOrNull()?.opcode != Opcode.CONST_4,
            "FacebookInitProvider.onCreate() must not be forced - login needs sdkInitialize() " +
                "(issue #24)\n$diagnostics"
        )

        // Disable Play Integrity is intentionally absent: the Play Integrity classes are not
        // bundled in this build (the AppsFlyer SDK only references them, behind a catch), so
        // there is nothing to patch. The patch was removed as part of the 26.09.07 update.

        // Block marketing notifications: both promo sheets dismiss before rendering.
        assertDismissesEarly(
            require(PROMOTION_MODAL).methodsNamed("onViewCreated").single(),
            label = "PromotionModal.onViewCreated()", diagnostics
        )
        // The tennis promo sheet was removed in 26.09.28; assert it only where it exists.
        byType[TENNIS_PROMO_SHEET]?.let { sheet ->
            assertDismissesEarly(
                sheet.methodsNamed("onViewCreated").single(),
                label = "SofascoreAnalystTennisPromoBottomSheet.onViewCreated()", diagnostics
            )
        }
        // Promotion banners stay GONE.
        for (banner in PROMO_BANNERS) {
            val first = require(banner).method("setVisibility", listOf("I")).instructions().firstOrNull()
            assertTrue(
                first is NarrowLiteralInstruction && first.narrowLiteral == 8,
                "$banner.setVisibility does not force GONE; first instruction is ${first?.opcode}\n$diagnostics"
            )
        }

        // Firebase In-App Messaging: every display implementation returns at once.
        val fiamDisplays = classes.filter {
            "Lcom/google/firebase/inappmessaging/FirebaseInAppMessagingDisplay;" in it.interfaces
        }
        assertTrue(fiamDisplays.isNotEmpty(), "no FirebaseInAppMessagingDisplay implementation found\n$diagnostics")
        for (display in fiamDisplays) {
            assertReturnsEarlyVoid(
                display.methodsNamed("displayMessage").single(),
                label = "${display.type}.displayMessage()", diagnostics
            )
        }

        // Disable ads: the "Active subscription" upgrade sheet's queue predicate reads
        // getHasServerAds() as true, so the sheet is not queued for everyone.
        assertCallResultForced(
            modalPredicate(classes, "Active subscription", "PR_XAIST", diagnostics),
            expected = true, label = "Active subscription predicate getHasServerAds()", diagnostics
        ) { ref, _ -> ref.definingClass == USER_ACCOUNT && ref.name == "getHasServerAds" }

        // Disable rating prompts: the "Rating Info" predicate reads "ask_review_v3" as false.
        val ratingPredicate = modalPredicate(classes, "Rating Info", "ask_review_v3", diagnostics)
        val ratingInsns = ratingPredicate.instructions()
        val askReviewLoad = ratingInsns.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "ask_review_v3"
        }
        val askReviewCall = (askReviewLoad + 1 until ratingInsns.size).first {
            (ratingInsns[it] as? ReferenceInstruction)?.reference is MethodReference
        }
        assertCallResultForced(
            ratingPredicate, expected = false, label = "Rating Info predicate getBoolean(\"ask_review_v3\")", diagnostics
        ) { ref, i -> i == askReviewCall && ref.name == "getBoolean" }

        // Hide betting content. The helpers are R8-renamed; find them by key plus shape, like
        // the fingerprints, and check each shape is unique.
        fun staticWith(returnType: String, params: List<String>, string: (String) -> Boolean): Method {
            val found = classes.flatMap { it.methods }.filter { m ->
                m.isStatic() && m.returnType == returnType && m.params() == params && m.strings().any(string)
            }
            assertTrue(found.size == 1, "expected one static ($params)$returnType, found ${found.size}\n$diagnostics")
            return found.single()
        }
        assertForcedBoolean(
            staticWith("Z", listOf(CONTEXT, SHARED_PREFERENCES)) { it == "PREF_PROVIDER_ODDS_ENABLE" },
            expected = false, label = "odds decision (PREF_PROVIDER_ODDS_ENABLE)", diagnostics
        )
        for (getter in listOf("getAdditionalOdds", "getAdditionalOddsComparison", "getRecommendedPrematchOdds")) {
            assertForcedBoolean(
                require(EVENT_HEAD_FLAGS).method(getter, emptyList()),
                expected = false, label = "EventHeadFlags.$getter()", diagnostics
            )
        }
        val signUpLink = staticWith("Ljava/lang/String;", listOf(CONTEXT)) { it.startsWith(BET365_SIGN_UP_PREFIX) }
        val linkInsns = signUpLink.instructions()
        assertTrue(
            (linkInsns.getOrNull(0) as? NarrowLiteralInstruction)?.narrowLiteral == 0 &&
                linkInsns[0].opcode == Opcode.CONST_4 &&
                linkInsns.getOrNull(1)?.opcode == Opcode.RETURN_OBJECT &&
                (linkInsns[0] as OneRegisterInstruction).registerA ==
                (linkInsns[1] as OneRegisterInstruction).registerA,
            "Bet365 sign-up link does not return null first; got ${linkInsns.getOrNull(0)?.opcode}\n$diagnostics"
        )
        assertForcedBoolean(
            staticWith("Z", listOf(CONTEXT)) { it == "PREF_PROMOTIONS_ENABLE" },
            expected = false, label = "promotions setting (PREF_PROMOTIONS_ENABLE)", diagnostics
        )
        assertForcedBoolean(
            staticWith("Z", listOf(CONTEXT)) { it == "ODDS_PROVIDERS_DISABLED" },
            expected = true, label = "odds providers disabled (ODDS_PROVIDERS_DISABLED)", diagnostics
        )

        // GmsCore support + Spoof signature (MicroG RE push and Google login). The shared
        // assertion also checks ensurePlayServicesAvailable, here a(Context)V.
        assertGmsCoreSupport(classes, workDir, PKG, START_ACTIVITY, SIGNATURE_SHA1, expectCredentialRouting = true)
        assertSpoofSignature(classes, PKG, SIGNATURE_SHA1)
    }
}
