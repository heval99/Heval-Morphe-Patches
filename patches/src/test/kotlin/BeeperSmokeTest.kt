import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.beeper.android"
private const val VERSION = "4.64.0"
private const val PLAN_PREFIX = "Lcom/beeper/chat/booper/connect/viewmodel/BeeperPlan$"
private const val PLANS = 5

class BeeperSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Enable Premium lifts the plan and freebie limits`() {
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
            patchNames = setOf("Enable Premium"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        // 1. Every concrete plan answers -1 for both account caps.
        val plans = classes.filter { it.type.startsWith(PLAN_PREFIX) && it.superclass == "Lcom/beeper/chat/booper/connect/viewmodel/BeeperPlan;" }
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
        val insns = consumers.single().instructions().toList()
        check(
            insns.getOrNull(0)?.opcode == Opcode.SGET_OBJECT &&
                insns.getOrNull(1)?.opcode == Opcode.RETURN_OBJECT,
        ) { "the freebie consumer does not return at once; got ${insns.take(3).map { it.opcode }}" }
    }
}
