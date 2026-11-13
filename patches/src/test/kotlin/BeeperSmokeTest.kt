import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.beeper.android"
private const val VERSION = "4.64.0"
private const val PLAN_PREFIX = "Lcom/beeper/chat/booper/connect/viewmodel/BeeperPlan$"
private const val PLANS = 5
private const val SENTRY_INIT_PROVIDER = "Lio/sentry/android/core/SentryInitProvider;"
private const val MAIN_ACTIVITY = "Lcom/beeper/chat/booper/MainActivity;"
private const val SIGNATURE_SHA1 = "d6ef1da902f51ff60deb84e4bf80ef71cc00c090"

class BeeperSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Enable Premium lifts the plan and freebie limits and telemetry is off`() {
        val root = repoRoot()
        val apk = File(root, "apks/beeper/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = VERSION,
            patchNames = setOf("Enable Premium", "Disable telemetry", "GmsCore support"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        // 1. Every concrete plan answers -1 for both account caps.
        val plans = classes.filter {
            it.type.startsWith(PLAN_PREFIX) &&
                it.superclass == "Lcom/beeper/chat/booper/connect/viewmodel/BeeperPlan;"
        }
        check(plans.size == PLANS) { "plan subclasses: ${plans.map { it.type }}" }
        for (plan in plans) {
            for (name in listOf("getMaxAccounts", "getMaxAccountsPerBridge")) {
                val method = plan.methods.singleOrNull {
                    it.name == name && it.returnType == "Ljava/lang/Integer;" && it.implementation != null
                } ?: error("${plan.type}->$name not found")
                val insns = method.instructions().toList()
                val first = insns.firstOrNull() as? NarrowLiteralInstruction
                check(first != null && first.opcode == Opcode.CONST_4 && first.narrowLiteral == -1) {
                    "${plan.type}->$name does not start with -1; got ${insns.take(2).map { it.opcode }}"
                }
                check(insns.any { it.opcode == Opcode.RETURN_OBJECT }) {
                    "${plan.type}->$name never returns the Integer"
                }
            }
        }

        // 2. The single freebie consumer returns the Unit instance immediately.
        val consumers = classes.flatMap { it.methods }.filter { method ->
            method.returnType == "Ljava/lang/Object;" &&
                method.implementation != null &&
                method.parameterTypes.map(CharSequence::toString) == listOf(
                    "Lcom/beeper/freebies/FreebiesRepository\$Freebie;",
                    "Lcom/beeper/freebies/FreebiePromptSurface;",
                    "Ljava/lang/String;",
                    "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
                )
        }
        check(consumers.size == 1) { "freebie consumers: ${consumers.map { "${it.definingClass}->${it.name}" }}" }
        val freebieInsns = consumers.single().instructions().toList()
        check(
            freebieInsns.getOrNull(0)?.opcode == Opcode.SGET_OBJECT &&
                freebieInsns.getOrNull(1)?.opcode == Opcode.RETURN_OBJECT,
        ) { "the freebie consumer does not return at once; got ${freebieInsns.take(3).map { it.opcode }}" }

        // 3. The Sentry init both start paths share returns at once.
        val provider = classes.singleOrNull { it.type == SENTRY_INIT_PROVIDER }
            ?: error("$SENTRY_INIT_PROVIDER not found in the emitted dexes")
        val onCreate = provider.methods.single {
            it.name == "onCreate" && it.returnType == "Z" && it.parameterTypes.isEmpty()
        }
        val init = onCreate.instructions()
            .filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }
            .singleOrNull { ref ->
                ref.returnType == "V" &&
                    ref.parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;" &&
                    ref.parameterTypes.drop(1).all { it.toString().startsWith("Lio/sentry/") }
            } ?: error("no Sentry init call in $SENTRY_INIT_PROVIDER->onCreate")
        val target = classes.singleOrNull { it.type == init.definingClass }
            ?: error("${init.definingClass} not found in the emitted dexes")
        val initMethod = target.methods.single {
            it.name == init.name && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == init.parameterTypes.map(CharSequence::toString)
        }
        check(initMethod.instructions().firstOrNull()?.opcode == Opcode.RETURN_VOID) {
            "${init.definingClass}->${init.name} does not return at once"
        }

        // 4. GmsCore support: the spoofing metadata and the MicroG RE check are in place.
        assertGmsCoreSupport(
            classes, workDir, PKG, MAIN_ACTIVITY, SIGNATURE_SHA1,
            expectAdsClasses = false,
            expectServiceCheck = false,
        )
    }
}
