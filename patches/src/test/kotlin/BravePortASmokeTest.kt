import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val SENSORS = "Lorg/chromium/device/sensors/PlatformSensorProvider;"
private const val SYNC = "Lorg/chromium/chrome/browser/background_sync/BackgroundSyncBackgroundTaskScheduler;"
private const val TABBED = "Lorg/chromium/chrome/browser/ChromeTabbedActivity;"

class BravePortASmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun Instruction.hasString(value: String) =
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == value

    private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.literal() = (this as? NarrowLiteralInstruction)?.narrowLiteral

    @Test
    fun `Brave batch A patches rewrite their gates`() {
        val root = repoRoot()
        val apk = File(root, "apks/brave/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = "com.brave.browser",
            version = "1.100.59",
            patchNames = setOf(
                "Stop promo notifications",
                "Turn off Brave News",
                "Block motion sensors",
                "Stop background sync wakeups",
                "Preserve last active tab",
            ),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val methods: List<Method> = classes.flatMap { it.methods }

        fun find(label: String, predicate: (Method) -> Boolean): Method {
            val hits = methods.filter { it.implementation != null && predicate(it) }
            assertEquals(1, hits.size, "$label: expected one method, found ${hits.map { "${it.definingClass}->${it.name}" }}")
            return hits.single()
        }

        fun Method.hasString(value: String) = instructions().any { it.hasString(value) }
        fun Method.params() = parameterTypes.map { it.toString() }
        // The scheduler's first parameter is a Context, but R8 may narrow it on other builds, so any object type counts.
        fun String.isObject() = startsWith("L")

        // Stop promo notifications: the three retention scheduling entry points (shape + kept
        // strings) return at once; onReceive and the posting method are left to Hide promotional prompts.
        val dormant = find("dormant scheduler") {
            it.params().size == 1 && it.params()[0].isObject() && it.returnType == "V" && it.hasString("dormant_users_days_14")
        }
        val alarm = find("alarm scheduler") {
            it.params().size == 2 && it.params()[0].isObject() && it.params()[1] == "Ljava/lang/String;" &&
                it.returnType == "V" && it.hasString("notification_type")
        }
        val alarmAt = find("timed alarm scheduler") {
            it.params().size == 3 && it.params()[0].isObject() && it.params()[1] == "Ljava/lang/String;" &&
                it.params()[2] == "J" && it.returnType == "V" && it.hasString("notification_type")
        }
        assertReturnsEarlyVoid(dormant, "dormant users scheduler")
        assertReturnsEarlyVoid(alarm, "retention alarm scheduler")
        assertReturnsEarlyVoid(alarmAt, "retention timed alarm scheduler")
        val tips = find("tips opt-in promo") {
            it.name == "onResult" && it.hasString("Chrome.TipsNotifications.OptInShowCount")
        }
        assertReturnsEarlyVoid(tips, "tips notifications opt-in promo")

        // Turn off Brave News: the (Profile)Z policy check now returns true at once.
        val news = find("Brave News policy check") {
            it.returnType == "Z" &&
                it.params() == listOf("Lorg/chromium/chrome/browser/profiles/Profile;") &&
                it.hasString("brave.news.disabled_by_policy")
        }
        assertForcedBoolean(news, true, "Brave News disabled-by-policy check")

        // Block motion sensors.
        val sensors = classes.single { it.type == SENSORS }
        assertForcedBoolean(sensors.method("hasSensorType", listOf("I")), false, "PlatformSensorProvider.hasSensorType")
        val create = sensors.method("create")
        assertChangedFromStock(create, "PlatformSensorProvider.create")
        val ci = create.instructions()
        val nullCall = ci.indexOfFirst { it.methodRef()?.name == "setSensorManagerToNullForTesting" }
        assertTrue(nullCall >= 0 && ci[nullCall + 1].opcode == Opcode.RETURN_OBJECT,
            "create() must null the SensorManager right before returning the provider")

        // Stop background sync wakeups: gate cancels job types 0 and 1, then reports false.
        val sync = classes.single { it.type == SYNC }.method("scheduleOneOffTask", listOf("I", "J"))
        assertChangedFromStock(sync, "scheduleOneOffTask")
        val si = sync.instructions()
        assertEquals(listOf(0, 1), listOf(0, 2).map { si[it].literal() }, "cancel arguments are the job types 0 and 1")
        assertEquals(listOf("cancelOneOffTask", "cancelOneOffTask"), listOf(1, 3).map { si[it].methodRef()?.name })
        assertEquals(0, si[4].literal(), "scheduleOneOffTask returns the literal 0 (false)")
        assertEquals(Opcode.RETURN, si[5].opcode, "scheduleOneOffTask returns right after the cancels")

        // Preserve last active tab: the opening-screen check returns false at once.
        val opening = find("opening screen check") {
            it.definingClass == TABBED && it.returnType == "Z" && it.parameterTypes.isEmpty() &&
                it.hasString("brave_new_tab_page_opening_screen")
        }
        assertForcedBoolean(opening, false, "ChromeTabbedActivity opening screen check")
    }
}
