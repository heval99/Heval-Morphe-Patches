import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "com.alarmclock.xtreme.free"
private const val SHOP_FEATURE = "Lcom/alarmclock/xtreme/shop/data/ShopFeature;"

class AlarmClockSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable ads reports the AD_FREE shop feature as owned`() {
        val root = repoRoot()
        val apk = File(root, "apks/alarmclock/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "26.06.0",
            patchNames = setOf("Disable ads"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        // The class is R8-renamed, so find the entitlement implementation by its shape.
        val check = classes.flatMap { cls ->
            cls.methods.filter {
                it.name == "d" && it.returnType == "Z" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(SHOP_FEATURE) &&
                    it.implementation != null
            }
        }.singleOrNull() ?: error("shop entitlement check d(ShopFeature)Z not found")

        val insns = check.instructions().toList()
        val first = insns.getOrNull(0)
        assertEquals(Opcode.CONST_STRING, first?.opcode, "AD_FREE check must start the method")
        assertEquals(
            "AD_FREE",
            ((first as? ReferenceInstruction)?.reference as? StringReference)?.string,
            "first string compared must be AD_FREE",
        )
    }
}
