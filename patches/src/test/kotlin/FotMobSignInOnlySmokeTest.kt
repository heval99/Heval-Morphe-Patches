import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val SIGN_IN_PKG = "com.mobilefootie.wc2010"
private const val SIGN_IN_MAIN_ACTIVITY = "Lcom/fotmob/android/ui/MainActivity;"
private const val SIGN_IN_ACTION = "com.google.android.gms.auth.api.identity.service.signin.START"
private const val MICROG = "app.revanced.android.gms"
private const val GOOGLE_GMS = "com.google.android.gms"
private const val GMS_EXTENSION_CLASS = "Lapp/template/extension/gmscore/GmsCoreSupport;"
private const val PLAY_SERVICES_PROVIDER = "Landroidx/credentials/playservices/CredentialProviderPlayServicesImpl;"
private const val BEGIN_SIGN_IN_UTILITY = "Landroidx/credentials/playservices/controllers/identityauth/beginsignin/BeginSignInControllerUtility"

// "GmsCore support" with its "signInOnly" option on (#106/#107): only the Identity sign-in client
// binds to MicroG RE, push (FCM) and every other Google Play services client stay on the real one.
class FotMobSignInOnlySmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun ClassDef.constStrings(): List<String> =
        methods.flatMap { m -> m.instructions().mapNotNull { it.stringOrNull() } }

    private fun List<ClassDef>.methodsHolding(value: String) =
        flatMap { cls -> cls.methods.filter { m -> m.instructions().any { it.stringOrNull() == value } }.map { cls to it } }

    @Test
    fun `GmsCore support in sign-in-only mode routes only the sign-in client`() {
        val root = repoRoot()
        val apk = File(root, "apks/fotmob/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = SIGN_IN_PKG,
            version = "242.18410.20261117",
            patchNames = setOf("GmsCore support", "Spoof signature"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
            options = mapOf("GmsCore support" to mapOf("signInOnly" to true)),
        )
        val stock = patchedApkClasses(apk)
        val byType = classes.associateBy { it.type }

        // 1. The extension runs first in the launcher's onCreate, through the sign-in-only entry point.
        assertTrue(GMS_EXTENSION_CLASS in byType, "gmscore extension not merged")
        val entry = byType.getValue(GMS_EXTENSION_CLASS).method("checkGmsCoreSignInOnly", listOf("Landroid/app/Activity;"))
        assertTrue(
            AccessFlags.STATIC.isSet(entry.accessFlags) && entry.implementation != null,
            "$GMS_EXTENSION_CLASS.checkGmsCoreSignInOnly(Activity) is not a static method with code",
        )
        val onCreate = byType.getValue(SIGN_IN_MAIN_ACTIVITY).method("onCreate", listOf("Landroid/os/Bundle;"))
        val first = onCreate.instructions().first()
        val call = (first as ReferenceInstruction).reference as MethodReference
        assertTrue(
            first.opcode == Opcode.INVOKE_STATIC_RANGE && call.definingClass == GMS_EXTENSION_CLASS &&
                call.name == "checkGmsCoreSignInOnly",
            "MainActivity.onCreate does not start with checkGmsCoreSignInOnly(p0); got ${first.opcode} $call",
        )

        // 2. Manifest: push stays on Google Play services (c2dm untouched, no renames), MicroG RE
        // meta-data and queries are there, package unchanged.
        val manifestFile = workDir.walkTopDown()
            .firstOrNull { it.name == "AndroidManifest.xml" && it.readText().contains("SPOOFED_PACKAGE_NAME") }
            ?: error("no decoded AndroidManifest.xml with the MicroG meta-data under $workDir")
        val manifestText = manifestFile.readText()
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile).documentElement
        assertEquals(SIGN_IN_PKG, manifest.getAttribute("package"))
        assertTrue("com.google.android.c2dm.permission.RECEIVE" in manifestText, "c2dm RECEIVE permission was renamed")
        assertTrue("app.revanced.android.c2dm" !in manifestText, "a c2dm manifest entry points at MicroG RE")
        fun Element.children(tag: String) = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
        val metaData = manifest.children("meta-data").associate { it.getAttribute("android:name") to it.getAttribute("android:value") }
        assertEquals(SIGN_IN_PKG, metaData["app.revanced.android.gms.SPOOFED_PACKAGE_NAME"])
        assertEquals(MICROG, metaData["app.revanced.MICROG_PACKAGE_NAME"])
        // Google Play services is queried too: the extension's "not installed" toast needs to see it (Android 11+).
        val queried = manifest.children("queries").flatMap { it.children("package") }.map { it.getAttribute("android:name") }
        assertTrue(MICROG in queried && GOOGLE_GMS in queried, "<queries> misses $MICROG or $GOOGLE_GMS: $queried")

        // 3. The Identity sign-in client: its action moved to MicroG RE, its start-service package
        // answers MicroG RE and its dynamic lookup is off (both overrides start the method).
        val clients = classes.filter { SIGN_IN_ACTION.replace("com.google", "app.revanced") in it.constStrings() }
        assertEquals(1, clients.size, "classes holding the MicroG sign-in action: ${clients.map { it.type }}")
        val client = clients.single()
        val packageOverrides = client.methods.filter { m ->
            m.parameterTypes.isEmpty() && m.returnType == "Ljava/lang/String;" &&
                m.instructions().firstOrNull()?.stringOrNull() == MICROG &&
                m.instructions().getOrNull(1)?.opcode == Opcode.RETURN_OBJECT
        }
        assertEquals(1, packageOverrides.size, "${client.type}: start-service package overrides ${client.methods.map { it.name }}")
        val lookupOverrides = client.methods.filter { m ->
            m.parameterTypes.isEmpty() && m.returnType == "Z" &&
                (m.instructions().firstOrNull() as? NarrowLiteralInstruction)?.narrowLiteral == 0 &&
                m.instructions().getOrNull(1)?.opcode == Opcode.RETURN
        }
        assertEquals(1, lookupOverrides.size, "${client.type}: dynamic-lookup overrides")
        assertTrue(client.constStrings().none { it == SIGN_IN_ACTION }, "the sign-in action still names Google Play services")
        // Each override answers a getter that one ancestor declares, non-final (found by name). The client extends BaseGmsClient through an abstract intermediate class (Lwhg; -> Llp5; -> Lwq0;).
        val ancestors = generateSequence(byType[client.superclass]) { byType[it.superclass] }.toList()
        assertTrue(ancestors.size >= 1, "${client.type} has no app superclass")
        for (override in packageOverrides + lookupOverrides) {
            val declaring = ancestors.filter { a ->
                a.methods.any { it.name == override.name && it.returnType == override.returnType && it.parameterTypes.isEmpty() }
            }
            assertEquals(1, declaring.size, "${client.type}->${override.name} is declared by ${declaring.map { it.type }}")
            assertTrue(
                declaring.single().methods.single { it.name == override.name && it.parameterTypes.isEmpty() }
                    .let { !AccessFlags.FINAL.isSet(it.accessFlags) && it.implementation != null },
                "${declaring.single().type}->${override.name} is final or abstract",
            )
        }

        // 4. The base getter and every other client stay on Google Play services: exactly one
        // "com.google.android.gms" constant was rewritten (BeginSignInControllerUtility's version read).
        val stockHolders = stock.methodsHolding(GOOGLE_GMS)
        val patchedHolders = classes.methodsHolding(GOOGLE_GMS)
        assertEquals(stockHolders.size - 1, patchedHolders.size, "methods holding $GOOGLE_GMS: stock ${stockHolders.size}")
        val gone = stockHolders.map { (c, m) -> c.type to m.name } - patchedHolders.map { (c, m) -> c.type to m.name }.toSet()
        assertTrue(
            gone.size == 1 && gone.single().first.startsWith(BEGIN_SIGN_IN_UTILITY) && gone.single().second == "determineDeviceGMSVersionCode",
            "the one rewritten $GOOGLE_GMS holder is $gone",
        )
        assertTrue(
            byType.getValue(gone.single().first).methods.single { it.name == "determineDeviceGMSVersionCode" }
                .instructions().any { it.stringOrNull() == MICROG },
            "determineDeviceGMSVersionCode does not read MicroG RE",
        )
        // Push and the rest of Firebase keep the c2dm strings, and nothing else moved.
        val c2dm = "com.google.android.c2dm.intent.REGISTER"
        assertEquals(stock.methodsHolding(c2dm).size, classes.methodsHolding(c2dm).size, "c2dm REGISTER holders changed")
        assertTrue(stock.methodsHolding(c2dm).isNotEmpty(), "stock has no c2dm REGISTER constant: wrong anchor")
        assertEquals(
            stock.methodsHolding("com.google").size, classes.methodsHolding("com.google").size,
            "com.google account type holders changed",
        )

        // 5. Google Play services is not reported as available: the availability check and the
        // ensure helper keep their stock code.
        // Spoof signature turns getPackageInfo calls into static calls, so compare the strings and the length, not the opcodes.
        fun Method.shape() = instructions().let { insns -> insns.size to insns.mapNotNull { it.stringOrNull() } }
        val availability = stock.flatMap { c -> c.methods.map { c.type to it } }.filter { (_, m) ->
            m.returnType == "I" && m.parameterTypes.size == 2 &&
                m.instructions().any { it.stringOrNull() == " requires Google Play services, but their signature is invalid." }
        }
        assertEquals(1, availability.size, "isGooglePlayServicesAvailable in stock: $availability")
        val (availabilityType, stockAvailability) = availability.single()
        val patchedAvailability = byType.getValue(availabilityType).methods.single {
            it.name == stockAvailability.name && it.parameterTypes == stockAvailability.parameterTypes
        }
        assertEquals(stockAvailability.shape(), patchedAvailability.shape(), "isGooglePlayServicesAvailable changed")
        val stockService = stock.flatMap { c -> c.methods.map { c.type to it } }.filter { (_, m) ->
            m.returnType == "V" && m.name != "<init>" && m.instructions().any { it.stringOrNull() == "Google Play Services not available" }
        }
        for ((type, m) in stockService) {
            val patched = byType.getValue(type).methods.single { it.name == m.name && it.parameterTypes == m.parameterTypes }
            assertEquals(m.shape(), patched.shape(), "ensurePlayServicesAvailable changed")
        }

        // 6. androidx.credentials: the framework provider reports unavailable, so Credential Manager
        // uses its Play services provider (which reaches the sign-in client above).
        val provider = byType.getValue(PLAY_SERVICES_PROVIDER)
        val providerInterface = provider.interfaces.single { type ->
            byType[type]?.methods?.any { it.name == "isAvailableOnDevice" && it.parameterTypes.isEmpty() } == true
        }
        val framework = classes.filter { cls ->
            providerInterface in cls.interfaces && cls.fields.any {
                it.type == "Landroid/credentials/CredentialManager;" && !AccessFlags.STATIC.isSet(it.accessFlags)
            }
        }
        assertEquals(1, framework.size, "framework credential providers: ${framework.map { it.type }}")
        val isAvailable = framework.single().methods.single { it.name == "isAvailableOnDevice" && it.parameterTypes.isEmpty() && it.implementation != null }
        assertForcedBoolean(isAvailable, expected = false, label = "${framework.single().type}.isAvailableOnDevice")

        // 7. Google ID requests take the BeginSignIn flow: every isAvailableOnDevice(int) test in
        // onGetCredential reads false (const/16 0 right after the call).
        var routedTests = 0
        provider.methods.filter { it.name == "onGetCredential" }.forEach { m ->
            val insns = m.instructions()
            insns.forEachIndexed { i, insn ->
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                if (ref.definingClass != provider.type || ref.name != "isAvailableOnDevice" || ref.parameterTypes.size != 1) return@forEachIndexed
                val next = insns.getOrNull(i + 1)
                assertTrue(
                    next?.opcode == Opcode.CONST_16 && (next as NarrowLiteralInstruction).narrowLiteral == 0 &&
                        (next as OneRegisterInstruction).registerA >= 0,
                    "onGetCredential still uses the isAvailableOnDevice(int) result; got ${next?.opcode}",
                )
                routedTests++
            }
        }
        assertTrue(routedTests > 0, "no isAvailableOnDevice(int) test found in onGetCredential")

        // 8. Spoof signature stays (Firebase checks the certificate too) and the onboarding auto
        // sign-in is still skipped.
        assertSpoofSignature(classes, SIGN_IN_PKG, "5546e6c4b0c1f31b9e5a953a89a8bb6f25a2ad6f")
        val noAccountLog = "Did not find any existing Google accounts already used for logging"
        fun List<ClassDef>.autoSignIn() = flatMap { cls -> cls.methods.filter { m -> m.instructions().any { it.stringOrNull()?.startsWith(noAccountLog) == true } } }
            .single()
        assertEquals(
            stock.autoSignIn().instructions().count { it.opcode == Opcode.THROW } + 1,
            classes.autoSignIn().instructions().count { it.opcode == Opcode.THROW },
            "the onboarding auto sign-in does not throw NoCredentialException",
        )
    }
}
