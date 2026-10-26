import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PKG = "bitpit.launcher"
private const val ANALYTICS_DEACTIVATED_KEY = "firebase_analytics_collection_deactivated"
private const val CRASHLYTICS_USER_ID_KEY = "bitpit.launcher.key.CRASHLYTICS_USER_ID"

private val TELEMETRY_WORKERS = listOf(
    "Lbitpit/launcher/analytics/consent/GdprConsentSyncWorker;",
    "Lbitpit/launcher/analytics/RetentionEventsWorker;",
    "Lbitpit/launcher/analytics/singular/SingularFirstSessionReportWorker;",
    "Lbitpit/launcher/analytics/singular/SingularSessionReportWorker;",
    "Lbitpit/launcher/analytics/singular/SingularEventReportWorker;",
    "Lbitpit/launcher/usage/report/UsageReportUploadWorker;",
)

class NiagaraSmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun Method.strings(): List<String> = instructions().mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
    }

    /** The one class whose toString holds [text] (R8-renamed library classes, found like the patch). */
    private fun List<ClassDef>.byToString(text: String): ClassDef {
        val found = filter { cls -> cls.methods.any { it.name == "toString" && text in it.strings() } }
        assertEquals(1, found.size, "classes with a \"$text\" toString: ${found.map { it.type }}")
        return found.single()
    }

    @Test
    fun `Disable telemetry stops the telemetry workers, Singular, Crashlytics and Analytics`() {
        val root = repoRoot()
        val apk = File(root, "apks/niagara/base.apk")

        // The APK lives in the gitignored apks/ directory. Skip rather than fail when it is
        // absent, so CI stays green; locally it is present and the test really runs.
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "1.16.33",
            patchNames = setOf("Disable telemetry"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val diagnostics = "emitted classes=${classes.size}"
        val byType = classes.associateBy { it.type }

        // 1. Every telemetry worker's renamed doWork starts with Result.success(): the call to
        // Result's only static no-arg method returning Success, whose result is returned.
        val success = classes.byToString("Success {mOutputData=")
        val result = byType.getValue(success.superclass!!)
        val factories = result.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == success.type
        }
        assertEquals(1, factories.size, "${result.type} success() factories: ${factories.map { it.name }}")
        val factory = factories.single()
        for (worker in TELEMETRY_WORKERS) {
            val doWorks = byType.getValue(worker).methods.filter {
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<init>" &&
                    it.parameterTypes.size == 1 && it.returnType == "Ljava/lang/Object;" &&
                    it.implementation != null
            }
            assertEquals(1, doWorks.size, "$worker doWork candidates: ${doWorks.map { it.name }}")
            val doWork = doWorks.single()
            val label = "$worker->${doWork.name}"
            assertReturnsMethodCall(doWork, result.type, factory.name, label, diagnostics)
            val call = (doWork.instructions().first() as ReferenceInstruction).reference as MethodReference
            assertTrue(
                call.parameterTypes.isEmpty() && call.returnType == success.type,
                "$label calls $call, not ${result.type}->${factory.name}()${success.type}"
            )
        }

        // 2. The Singular request sender (the only method with the "attribution" path) returns the
        // R8-renamed kotlin.Unit INSTANCE first.
        val unit = classes.byToString("kotlin.Unit")
        val senders = classes.flatMap { it.methods }.filter { "attribution" in it.strings() }
        assertEquals(1, senders.size, "\"attribution\" methods: ${senders.map { "${it.definingClass}->${it.name}" }}")
        val sender = senders.single()
        val senderLabel = "${sender.definingClass}->${sender.name}"
        assertEquals(
            listOf("Ljava/lang/String;", "Lkotlinx/serialization/KSerializer;"),
            sender.parameterTypes.map(CharSequence::toString).let { listOf(it[0], it[2]) },
            "$senderLabel parameters"
        )
        val senderInsns = sender.instructions()
        val unitRead = senderInsns[0]
        val unitField = (unitRead as? ReferenceInstruction)?.reference as? FieldReference
        assertTrue(
            unitRead.opcode == Opcode.SGET_OBJECT && unitField?.definingClass == unit.type && unitField.type == unit.type &&
                senderInsns[1].opcode == Opcode.RETURN_OBJECT &&
                (senderInsns[1] as OneRegisterInstruction).registerA == (unitRead as OneRegisterInstruction).registerA,
            "$senderLabel does not return ${unit.type} first; got ${senderInsns.take(2).map { it.opcode }} $unitField\n$diagnostics"
        )

        // 3. Crashlytics: in the class whose constructor throws "FirebaseCrashlytics component is
        // not present.", the ()V enable method (putString of the user id key) returns at once and
        // the disable twin (remove) keeps its code.
        val switches = classes.filter { cls ->
            cls.methods.any { it.name == "<init>" && "FirebaseCrashlytics component is not present." in it.strings() }
        }
        assertEquals(1, switches.size, "Crashlytics switch classes: ${switches.map { it.type }}")
        val keyed = switches.single().methods.filter {
            it.returnType == "V" && it.parameterTypes.isEmpty() && CRASHLYTICS_USER_ID_KEY in it.strings()
        }
        fun Method.callsEditor(name: String) = instructions().any {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Landroid/content/SharedPreferences\$Editor;" && ref.name == name
        }
        val enables = keyed.filter { it.callsEditor("putString") }
        val disables = keyed.filter { it.callsEditor("remove") }
        assertEquals(1, enables.size, "Crashlytics enable methods: ${enables.map { it.name }}")
        assertEquals(1, disables.size, "Crashlytics disable methods: ${disables.map { it.name }}")
        assertReturnsEarlyVoid(enables.single(), "Crashlytics enable ${enables.single().name}", diagnostics)
        assertTrue(
            disables.single().instructions().first().opcode != Opcode.RETURN_VOID,
            "the Crashlytics disable method was changed"
        )

        // 4. The single "firebase_analytics_collection_deactivated" read is overwritten with
        // Boolean.TRUE after its move-result.
        val readers = classes.flatMap { it.methods }.filter { ANALYTICS_DEACTIVATED_KEY in it.strings() }
        assertEquals(1, readers.size, "$ANALYTICS_DEACTIVATED_KEY readers: ${readers.map { "${it.definingClass}->${it.name}" }}")
        val reader = readers.single()
        val readLabel = "${reader.definingClass}->${reader.name} ($ANALYTICS_DEACTIVATED_KEY)"
        val readInsns = reader.instructions()
        val keyIndex = readInsns.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == ANALYTICS_DEACTIVATED_KEY
        }
        val keyRegister = (readInsns[keyIndex] as OneRegisterInstruction).registerA
        val read = readInsns[keyIndex + 1]
        val readRef = (read as? ReferenceInstruction)?.reference as? MethodReference
        assertTrue(
            read is FiveRegisterInstruction && read.registerCount == 2 && read.registerD == keyRegister &&
                readRef?.returnType == "Ljava/lang/Boolean;" &&
                readRef.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;"),
            "$readLabel: the key is not read through a Boolean get(String); got ${read.opcode} $readRef"
        )
        val move = readInsns[keyIndex + 2]
        val forced = readInsns[keyIndex + 3]
        val forcedField = (forced as? ReferenceInstruction)?.reference as? FieldReference
        assertTrue(
            move.opcode == Opcode.MOVE_RESULT_OBJECT && forced.opcode == Opcode.SGET_OBJECT &&
                forcedField?.definingClass == "Ljava/lang/Boolean;" && forcedField.name == "TRUE" &&
                (forced as OneRegisterInstruction).registerA == (move as OneRegisterInstruction).registerA,
            "$readLabel: the read is not overwritten with Boolean.TRUE; got ${readInsns.drop(keyIndex).take(4).map { it.opcode }}"
        )
    }
}
