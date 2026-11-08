import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PKG = "dk.weekendavisen.areader"
private const val VERSION = "8.4.0"
private const val ABSTRACT_TRACKER = "Lcom/visiolink/reader/base/tracking/AbstractTracker;"

private val TELEMETRY_META = mapOf(
    "firebase_analytics_collection_enabled" to "false",
    "firebase_analytics_collection_deactivated" to "true",
    "firebase_crashlytics_collection_enabled" to "false",
    "firebase_performance_collection_deactivated" to "true",
    "firebase_sessions_enabled" to "false",
    "google_analytics_adid_collection_enabled" to "false",
)

class WeekendavisenSmokeTest {

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
        val apk = File(root, "apks/weekendavisen/base.apk")

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
