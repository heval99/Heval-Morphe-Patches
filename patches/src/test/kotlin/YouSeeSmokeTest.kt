import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PKG = "yiuo.mobile.tv"
private const val VERSION = "13.11.0 (build 21977)"
private const val ANALYTICS_REGISTRAR =
    "Lcom/google/firebase/analytics/connector/internal/AnalyticsConnectorRegistrar;"

private val TELEMETRY_META = mapOf(
    "firebase_analytics_collection_deactivated" to "true",
    "firebase_crashlytics_collection_enabled" to "false",
    "firebase_performance_collection_deactivated" to "true",
    "firebase_sessions_enabled" to "false",
    "google_analytics_adid_collection_enabled" to "false",
)

class YouSeeSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable telemetry sets the Firebase switches and drops the analytics component`() {
        val root = repoRoot()
        val apk = File(root, "apks/yousee/base.apk")

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

        // 1. The analytics component registers nothing.
        val registrar = classes.singleOrNull { it.type == ANALYTICS_REGISTRAR }
            ?: error("$ANALYTICS_REGISTRAR not found in emitted dexes")
        val getComponents = registrar.methods.single {
            it.name == "getComponents" && it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/util/List;"
        }
        val insns = getComponents.instructions().toList()
        val first = insns.getOrNull(0)
        val field = (first as? ReferenceInstruction)?.reference as? FieldReference
        assertTrue(
            first?.opcode == Opcode.SGET_OBJECT && field?.definingClass == "Ljava/util/Collections;" &&
                field.name == "EMPTY_LIST" && insns.getOrNull(1)?.opcode == Opcode.RETURN_OBJECT,
            "$ANALYTICS_REGISTRAR.getComponents() does not return an empty component list; got " +
                insns.take(3).map { it.opcode },
        )

        // 2. The manifest switches are set exactly once on <application>.
        val manifestFile = workDir.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && it.readText().contains("firebase_analytics_collection_deactivated") }
            .firstOrNull() ?: error("no decoded AndroidManifest.xml with the telemetry meta-data under $workDir")
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile).documentElement
        fun Element.children(tag: String) =
            getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
        val application = manifest.children("application").single()
        val metaData = application.children("meta-data")
            .filter { it.parentNode == application }
            .groupBy({ it.getAttribute("android:name") }, { it.getAttribute("android:value") })
        for ((name, value) in TELEMETRY_META) {
            check(metaData[name] == listOf(value)) { "meta-data $name is ${metaData[name]}, expected [$value]" }
        }

        // 3. Only collection is switched off: Firebase component discovery stays declared.
        val services = application.children("service").map { it.getAttribute("android:name") }
        assertTrue(
            "com.google.firebase.components.ComponentDiscoveryService" in services,
            "Firebase component discovery was removed",
        )
    }
}
