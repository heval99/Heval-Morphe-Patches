import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "dk.herningfolkeblad.areader"
private const val VERSION = "15.0.0"

class HerningSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable telemetry no-ops the Textalk dispatcher and sets the Firebase switches`() {
        val root = repoRoot()
        val apk = File(root, "apks/herning/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = VERSION,
            patchNames = setOf("Disable telemetry"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        // 1. The one dispatcher every analytics event funnels through returns at once.
        val analytics = classes.singleOrNull { cls ->
            cls.fields.any { it.name == "reporters" && it.type == "Ljava/util/List;" } &&
                cls.methods.any { it.name == "invokeReporterWithCrashlyticsWrapper" }
        } ?: error("no Textalk Analytics dispatcher found in the emitted dexes")
        val dispatch = analytics.method("invokeReporterWithCrashlyticsWrapper")
        assertReturnsEarlyVoid(dispatch, "${analytics.type}->invokeReporterWithCrashlyticsWrapper")

        // The event helpers are untouched: send() still reaches the dispatcher, which now returns.
        val send = analytics.methods.singleOrNull {
            it.name == "send" && it.returnType == "V" && it.parameterTypes.size == 2
        } ?: error("Textalk Analytics.send not found")
        check(
            send.instructions().any { insn ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)
                    ?.name == "invokeReporterWithCrashlyticsWrapper"
            },
        ) { "Textalk Analytics.send no longer calls the dispatcher" }

        // 2. The Firebase switches are forced in the bytecode (no Performance in this app).
        assertFirebaseSwitchesForced(classes, analytics = true, crashlytics = true, sessions = true, performance = false)
    }
}
