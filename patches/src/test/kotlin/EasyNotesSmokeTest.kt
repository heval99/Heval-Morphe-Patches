import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "easynotes.notes.notepad.notebook.privatenotes.note"
private const val APP_CLASS = "Lnotes/easy/android/mynotes/App;"

class EasyNotesSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `EasyNotes premium and ad-free patches force the App gates to return true`() {
        val root = repoRoot()
        val apk = File(root, "apks/easynotes/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        // The shared harness checks the fixture identity (package, versionName, versionCode),
        // runs the patches and reads the whole patched APK (every stock class must survive).
        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "1.3.66.1117",
            patchNames = setOf("Enable Premium", "Disable ads"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        val app = classes.firstOrNull { it.type == APP_CLASS } ?: error("$APP_CLASS not found in the patched APK")

        for (name in listOf("isVip", "isAdFree")) {
            val method = app.methods.firstOrNull { it.name == name && it.returnType == "Z" && it.implementation != null }
                ?: error("$APP_CLASS.$name()Z not found in the patched APK")
            val insns = method.instructions()
            val first = insns[0]
            val second = insns.getOrNull(1)

            assertTrue(
                first is NarrowLiteralInstruction &&
                    first.opcode == Opcode.CONST_4 &&
                    first.narrowLiteral == 1,
                "$name() was not forced true; first instruction is ${first.opcode}"
            )
            assertTrue(
                second != null && second.opcode == Opcode.RETURN,
                "$name() does not return immediately; second instruction is ${second?.opcode}"
            )
            assertTrue(
                (second as OneRegisterInstruction).registerA ==
                    (first as OneRegisterInstruction).registerA,
                "$name() returns a different register than the one set to 1"
            )
        }
    }
}
