import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "dk.information.areader"
private const val VERSION = "4.37.0"
private const val ABSTRACT_TRACKER = "Lcom/visiolink/reader/base/tracking/AbstractTracker;"

class InformationSmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun List<ClassDef>.isTracker(classDef: ClassDef): Boolean {
        var type = classDef.superclass
        var steps = 0
        while (type != null && steps < 8) {
            if (type == ABSTRACT_TRACKER) return true
            type = firstOrNull { it.type == type }?.superclass
            steps++
        }
        return false
    }

    @Test
    fun `Disable telemetry no-ops every Visiolink tracker and sets the Firebase switches`() {
        val root = repoRoot()
        val apk = File(root, "apks/information/base.apk")

        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = VERSION,
            patchNames = setOf("Disable telemetry"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        // 1. Every concrete AbstractTracker subclass has only early-returning void methods.
        val trackers = classes.filter { cls ->
            cls.type != ABSTRACT_TRACKER && !AccessFlags.ABSTRACT.isSet(cls.accessFlags) &&
                classes.isTracker(cls)
        }
        check(trackers.size >= 3) { "trackers: ${trackers.map { it.type }}" }
        var checked = 0
        trackers.forEach { tracker ->
            tracker.methods
                .filter { it.returnType == "V" && it.name != "<init>" && it.name != "<clinit>" }
                .forEach { method ->
                    check(method.instructions().firstOrNull()?.opcode == Opcode.RETURN_VOID) {
                        "${tracker.type}->${method.name} does not return at once"
                    }
                    checked++
                }
        }
        check(checked >= 10) { "only $checked tracker methods checked" }

        // 2. The Firebase Analytics switches are forced in the bytecode (no other Firebase SDK).
        assertFirebaseSwitchesForced(classes, analytics = true, crashlytics = false, sessions = false, performance = false)
    }
}
