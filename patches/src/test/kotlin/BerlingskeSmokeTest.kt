import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.Opcode
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PKG = "com.mecom.Berlingske"
private const val VERSION = "4.5.8"
private const val ANALYTICS = "Lcom/google/firebase/analytics/FirebaseAnalytics;"

private val TELEMETRY_META = mapOf(
    "firebase_analytics_collection_enabled" to "false",
    "firebase_analytics_collection_deactivated" to "true",
    "firebase_crashlytics_collection_enabled" to "false",
    "firebase_performance_collection_deactivated" to "true",
    "firebase_sessions_enabled" to "false",
    "google_analytics_adid_collection_enabled" to "false",
)

class BerlingskeSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `Disable telemetry stops the Firebase event methods and sets the switches`() {
        val root = repoRoot()
        val apk = File(root, "apks/berlingske/base.apk")

        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = VERSION,
            patchNames = setOf("Disable telemetry"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )

        // 1. The three Firebase Analytics event methods return at once.
        val analytics = classes.singleOrNull { it.type == ANALYTICS }
            ?: error("$ANALYTICS not found in emitted dexes")
        val eventMethods = analytics.methods.filter {
            it.returnType == "V" && (
                (it.name == "a" && it.parameterTypes.size == 2) ||
                    (it.name == "b" && it.parameterTypes.size == 2) ||
                    (it.name == "setCurrentScreen" && it.parameterTypes.size == 3)
                )
        }
        check(eventMethods.size == 3) { "event methods: ${eventMethods.map { it.name }}" }
        eventMethods.forEach { method ->
            check(method.instructions().firstOrNull()?.opcode == Opcode.RETURN_VOID) {
                "$ANALYTICS.${method.name} does not return at once"
            }
        }

        // 2. The Firebase switches are set exactly once on <application>.
        val manifestFile = workDir.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && it.readText().contains("firebase_crashlytics_collection_enabled") }
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
    }
}
