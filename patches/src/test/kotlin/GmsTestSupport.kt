import app.template.patches.shared.misc.gms.GOOGLE_SIGNATURE_INVALID_WARNING
import app.template.patches.shared.misc.gms.SERVICE_CHECK_MESSAGE
import app.template.patches.shared.misc.gms.isServiceCheckParameters
import app.template.patches.shared.misc.gms.gmsContentUriReplacement
import app.template.patches.shared.misc.gms.gmsStringReplacements
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

// Shared assertions for apps that use the "GmsCore support" and "Spoof signature" builders.

private const val GMS_EXTENSION = "Lapp/template/extension/gmscore/GmsCoreSupport;"
private const val SIGNATURE_EXTENSION = "Lapp/template/extension/signature/SignatureSpoof;"


internal fun Instruction.stringOrNull(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

// Registers of an invoke with exactly one argument register, else null.
private fun Instruction.singleRegister(): Int? = when (this) {
    is RegisterRangeInstruction -> startRegister.takeIf { registerCount == 1 }
    is FiveRegisterInstruction -> registerC.takeIf { registerCount == 1 }
    else -> null
}

/**
 * Checks the shared "GmsCore support" result: the MicroG RE check runs first in the launch
 * activity's onCreate, isGooglePlayServicesAvailable reports SUCCESS, every GMS constant points at
 * MicroG RE, and the manifest carries the spoofing meta-data, `<queries>` and c2dm renames.
 */
internal fun assertGmsCoreSupport(
    classes: List<ClassDef>,
    workDir: File,
    pkg: String,
    launchActivity: String,
    signatureSha1: String,
    // True when the app's androidx.credentials picks the Identity Credentials API by version.
    expectCredentialRouting: Boolean = false,
) {
    val byType = classes.associateBy { it.type }
    // The extension is merged, and the launch activity's onCreate calls it first with p0.
    val extension = byType[GMS_EXTENSION]
        ?: error("$GMS_EXTENSION (gmscore extension) not merged into the emitted dexes")
    val check = extension.method("checkGmsCore", listOf("Landroid/app/Activity;"))
    check(AccessFlags.STATIC.isSet(check.accessFlags) && check.implementation != null) {
        "$GMS_EXTENSION.checkGmsCore(Activity) is not a static method with code"
    }
    val onCreate = (byType[launchActivity] ?: error("$launchActivity not found")).method("onCreate", listOf("Landroid/os/Bundle;"))
    val first = onCreate.instructions().firstOrNull()
    val call = (first as? ReferenceInstruction)?.reference as? MethodReference
    check(
        (first?.opcode == Opcode.INVOKE_STATIC_RANGE || first?.opcode == Opcode.INVOKE_STATIC) &&
            call?.definingClass == GMS_EXTENSION && call.name == "checkGmsCore" &&
            first.singleRegister() == onCreate.implementation!!.registerCount - 2 // this + Bundle
    ) { "$launchActivity.onCreate does not start with checkGmsCore(p0); got ${first?.opcode} $call" }

    // isGooglePlayServicesAvailable (R8 may reorder its parameters or inline it into
    // GoogleApiAvailabilityLight) reports SUCCESS.
    val availability = classes.flatMap { cls -> cls.methods.map { cls to it } }.filter { (_, m) ->
        m.returnType == "I" && m.parameterTypes.size == 2 &&
            GOOGLE_SIGNATURE_INVALID_WARNING in m.instructions().mapNotNull { it.stringOrNull() }
    }
    check(availability.size == 1) { "expected one isGooglePlayServicesAvailable, found ${availability.map { (c, m) -> "${c.type}.${m.name}" }}" }
    val (availabilityClass, availabilityMethod) = availability.single()
    assertReturnsInt(availabilityMethod, 0, "${availabilityClass.type}.${availabilityMethod.name} (isGooglePlayServicesAvailable)")

    // ensurePlayServicesAvailable ((Context), (Context, int) or (int, Context)) returns at once.
    val serviceChecks = classes.flatMap { cls -> cls.methods.map { cls to it } }.filter { (_, m) ->
        m.returnType == "V" && isServiceCheckParameters(m.parameterTypes) &&
            SERVICE_CHECK_MESSAGE in m.instructions().mapNotNull { it.stringOrNull() }
    }
    check(serviceChecks.size == 1) { "expected one ensurePlayServicesAvailable, found ${serviceChecks.map { (c, m) -> "${c.type}.${m.name}" }}" }
    val (serviceCheckClass, serviceCheckMethod) = serviceChecks.single()
    assertReturnsEarlyVoid(serviceCheckMethod, "${serviceCheckClass.type}.${serviceCheckMethod.name} (ensurePlayServicesAvailable)")

    // No GMS package / action / authority constant is left, and the ad SDK is untouched.
    val replacements = gmsStringReplacements()
    val leftovers = classes.flatMap { cls ->
        cls.methods.flatMap { m ->
            m.instructions().mapNotNull { it.stringOrNull() }
                .filter { it in replacements || gmsContentUriReplacement(it) != null }
                .map { "${cls.type}.${m.name}: $it" }
        }
    }
    check(leftovers.isEmpty()) { "GMS strings were not rewritten:\n${leftovers.joinToString("\n")}" }
    val strings = classes.flatMap { cls -> cls.methods.flatMap { m -> m.instructions().mapNotNull { it.stringOrNull() } } }
    check("app.revanced.android.gms" in strings) { "no \"com.google.android.gms\" constant points at MicroG RE" }
    check(byType.keys.any { it.startsWith("Lcom/google/android/gms/ads/") }) { "com.google.android.gms.ads classes disappeared" }

    // Manifest: MicroG RE spoofing meta-data, <queries>, c2dm renames and the unchanged package.
    val manifestFile = workDir.walkTopDown()
        .filter { it.name == "AndroidManifest.xml" && it.readText().contains("SPOOFED_PACKAGE_NAME") }
        .firstOrNull() ?: error("no decoded AndroidManifest.xml with the MicroG meta-data under $workDir")
    val manifestText = manifestFile.readText()
    val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile).documentElement
    check(manifest.getAttribute("package") == pkg) { "package name changed to ${manifest.getAttribute("package")}" }

    fun Element.children(tag: String) =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
    val metaData = manifest.children("meta-data").associate { it.getAttribute("android:name") to it.getAttribute("android:value") }
    for ((name, value) in mapOf(
        "app.revanced.android.gms.SPOOFED_PACKAGE_NAME" to pkg,
        "app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE" to signatureSha1,
        "app.revanced.MICROG_PACKAGE_NAME" to "app.revanced.android.gms",
    )) {
        check(metaData[name] == value) { "meta-data $name is ${metaData[name]}, expected $value" }
    }
    val queried = manifest.children("queries").flatMap { it.children("package") }.map { it.getAttribute("android:name") }
    check("app.revanced.android.gms" in queried) { "<queries> is missing app.revanced.android.gms" }
    // FCM: the c2dm permission and receiver actions point at MicroG RE.
    check("com.google.android.c2dm.permission.RECEIVE" !in manifestText) { "c2dm RECEIVE permission was not renamed" }
    check("app.revanced.android.c2dm.permission.RECEIVE" in manifestText) { "MicroG c2dm RECEIVE permission missing" }

    // androidx.credentials 1.6+: onGetCredential no longer picks the Identity Credentials API
    // (its version tests read false), so Google ID requests use the BeginSignIn flow.
    val credentialProvider = byType["Landroidx/credentials/playservices/CredentialProviderPlayServicesImpl;"]
    var routedTests = 0
    credentialProvider?.methods?.filter { it.name == "onGetCredential" }?.forEach { m ->
        val insns = m.instructions()
        insns.forEachIndexed { i, insn ->
            val call = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
            if (call.definingClass != credentialProvider.type || call.name != "isAvailableOnDevice" || call.parameterTypes.size != 1) {
                return@forEachIndexed
            }
            val next = insns.getOrNull(i + 1)
            check(next?.opcode == Opcode.CONST_16 && (next as NarrowLiteralInstruction).narrowLiteral == 0) {
                "${credentialProvider.type}.onGetCredential still uses the isAvailableOnDevice(int) result; got ${next?.opcode}"
            }
            routedTests++
        }
    }
    if (expectCredentialRouting) {
        check(routedTests > 0) { "no isAvailableOnDevice(int) test found in CredentialProviderPlayServicesImpl.onGetCredential" }
    }
}

/**
 * Checks the shared "Spoof signature" result: the extension returns [pkg] and a certificate with
 * SHA-1 [signatureSha1], and no PackageManager.getPackageInfo(String, ...) call is left outside it.
 */
internal fun assertSpoofSignature(classes: List<ClassDef>, pkg: String, signatureSha1: String) {
    val byType = classes.associateBy { it.type }
    // The extension is merged with the original package name and certificate injected.
    val extension = byType[SIGNATURE_EXTENSION]
        ?: error("$SIGNATURE_EXTENSION (signature extension) not merged into the emitted dexes")
    fun returnedString(name: String): String? {
        val insns = extension.method(name, emptyList()).instructions()
        check(insns.getOrNull(1)?.opcode == Opcode.RETURN_OBJECT) { "$name() does not return immediately" }
        return insns.firstOrNull()?.stringOrNull()
    }
    check(returnedString("getSpoofedPackageName") == pkg) { "spoofed package name is not $pkg" }
    val certificate = Base64.getDecoder().decode(returnedString("getSpoofedCertificate") ?: error("no certificate injected"))
    val sha1 = MessageDigest.getInstance("SHA-1").digest(certificate).joinToString("") { "%02x".format(it) }
    check(sha1 == signatureSha1) { "spoofed certificate SHA-1 is $sha1, expected $signatureSha1" }

    // No app or library code calls PackageManager.getPackageInfo(String, ...) directly any more;
    // every call goes through the extension. Firebase's cert hash helper is among them.
    fun MethodReference.isGetPackageInfo() = name == "getPackageInfo" &&
        parameterTypes.map(CharSequence::toString).let { params ->
            when (definingClass) {
                SIGNATURE_EXTENSION -> params.getOrNull(0) == "Landroid/content/pm/PackageManager;" &&
                    params.getOrNull(1) == "Ljava/lang/String;"
                else -> params.firstOrNull() == "Ljava/lang/String;"
            }
        }
    val direct = mutableListOf<String>()
    var routed = 0
    for (cls in classes) {
        if (cls.type.startsWith("Lapp/template/extension/")) continue
        for (m in cls.methods) for (insn in m.instructions()) {
            val call = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (!call.isGetPackageInfo()) continue
            when (call.definingClass) {
                "Landroid/content/pm/PackageManager;" ->
                    if (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_VIRTUAL_RANGE) direct += "${cls.type}.${m.name}"
                SIGNATURE_EXTENSION -> routed++
            }
        }
    }
    check(direct.isEmpty()) { "direct getPackageInfo calls left:\n${direct.joinToString("\n")}" }
    check(routed >= 5) { "only $routed getPackageInfo calls routed through the extension" }
}
