import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "me.piebridge.brevent"
private const val FORCED_BREFOIL = 10

// The patch forces the count getter located by the 1185657756 XOR mask; after patching that
// literal is gone from the getter's body, so the test enters from the tier-5 gate instead
// (a no-arg boolean method that still holds the mask) and checks the ()I getters it sums.
private const val BREFOIL_XOR_MASK = 1185657756

class BreventSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Enable Premium forces the Brefoil count getter`() {
        val root = repoRoot()
        val apk = File(root, "apks/brevent/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "4.2.31.1",
            patchNames = setOf("Enable Premium"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        // The tier-5 gate (Q.Y1: v() + w() >= mask ^ threshold) keeps the mask literal.
        val tierGate = classes.flatMap { it.methods }.firstOrNull { method ->
            method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                method.instructions().any { insn ->
                    insn is NarrowLiteralInstruction && insn.narrowLiteral == BREFOIL_XOR_MASK
                }
        } ?: error("tier gate (no-arg boolean with mask literal) not found in emitted dexes")

        // The no-arg ()I getters it sums (v and w on the Application class).
        val getterRefs = tierGate.instructions().mapNotNull { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ref?.takeIf { it.parameterTypes.isEmpty() && it.returnType == "I" }
                ?.let { it.definingClass to it.name }
        }.toSet()
        assertTrue(getterRefs.isNotEmpty(), "tier gate calls no count getters")

        var forced = 0
        for ((type, name) in getterRefs) {
            val cls = classes.firstOrNull { it.type == type } ?: continue
            val getter = cls.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == "I"
            } ?: continue

            val insns = getter.instructions()
            val first = insns.getOrNull(0)
            if (first is NarrowLiteralInstruction &&
                first.narrowLiteral.toInt() == FORCED_BREFOIL &&
                insns.getOrNull(1)?.opcode == Opcode.RETURN
            ) {
                assertChangedFromStock(getter, "Brevent count getter ${cls.type}.$name")
                forced++
            }
        }

        // The patch forces exactly one getter (the single method holding the mask literal).
        assertTrue(
            forced == 1,
            "exactly one of the count getters summed by the tier gate ($getterRefs) must be forced " +
                "to $FORCED_BREFOIL (the patch forces the single mask getter); found $forced",
        )
    }
}
