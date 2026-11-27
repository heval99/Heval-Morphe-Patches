import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.util.ReferenceUtil
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import util.requirePatchesVersion
import util.selectPatchBundle
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Shared helpers for the per-app unlock smoke tests. Each test applies the app's patch to the
 * APK in the gitignored `apks/` directory and asserts on the emitted bytecode.
 */
fun repoRoot(): File {
    var dir = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
    return dir ?: File(System.getProperty("user.dir")).absoluteFile
}

/**
 * The bundle of the current project version, `patches/build/libs/patches-<version>.mpp`
 * (version passed in by patches/build.gradle.kts), never the newest-modified one: stale bundles
 * from older versions stay in build/libs. The name is kept for the existing call sites.
 */
fun newestPatchBundle(root: File): File =
    selectPatchBundle(File(root, "patches/build/libs"), requirePatchesVersion())

fun loadAllPatches(bundle: File): Set<Patch> = loadPatchesFromJar(setOf(bundle))

fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

fun ClassDef.method(name: String, parameters: List<String>? = null): Method =
    methods.firstOrNull { method ->
        method.name == name &&
            (parameters == null ||
                method.parameterTypes.map(CharSequence::toString) == parameters)
    } ?: error("$type.$name(${parameters?.joinToString()}) not found")

/**
 * The unpatched classes of the APK the last [applyPatches] call patched, so the assertion helpers can
 * prove a method was CHANGED rather than just look like a forced one (a method that already starts
 * with `const/4 v0, 0x1; return v0` or `return-void` in the stock app would otherwise pass without
 * the patch). Each test class runs in its own JVM (forkEvery = 1), so one global is enough.
 */
object StockMethods {
    private var byType: Map<String, ClassDef>? = null

    fun register(stock: Collection<ClassDef>) {
        byType = stock.associateBy { it.type }
    }

    fun clear() {
        byType = null
    }

    /** True once a stock snapshot is registered (always the case after [applyPatches]). */
    val available: Boolean get() = byType != null

    /** The stock method with the same class, name, parameters and return type, or null. */
    fun find(method: Method): Method? =
        byType?.get(method.definingClass)?.methods?.firstOrNull {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }
}

/** Opcode, registers, literal and reference of every instruction: equal lists mean identical code. */
private fun Method.instructionSignature(): List<String> = instructions().map { insn ->
    buildString {
        append(insn.opcode)
        if (insn is OneRegisterInstruction) append(" a=").append(insn.registerA)
        if (insn is TwoRegisterInstruction) append(" b=").append(insn.registerB)
        if (insn is ThreeRegisterInstruction) append(" c=").append(insn.registerC)
        if (insn is NarrowLiteralInstruction) append(" n=").append(insn.narrowLiteral)
        if (insn is WideLiteralInstruction) append(" w=").append(insn.wideLiteral)
        if (insn is ReferenceInstruction) append(" r=").append(ReferenceUtil.getReferenceString(insn.reference))
    }
}

/**
 * Negative control (audit 2026-11): fails when [method] has exactly the instructions it had in the
 * stock APK, i.e. the patch did not change it. A method the patch created (absent from the stock
 * snapshot) counts as changed. Skipped when no stock snapshot was registered (helper unit tests).
 */
fun assertChangedFromStock(method: Method, label: String) {
    if (!StockMethods.available) return
    val stock = StockMethods.find(method) ?: return
    assertTrue(
        stock.instructionSignature() != method.instructionSignature(),
        "$label is identical to the stock method; the patch did not change it (vacuous pass)"
    )
}

fun assertForcedBoolean(
    method: Method,
    expected: Boolean,
    label: String,
    diagnostics: String = "",
    requireChanged: Boolean = true,
) {
    if (requireChanged) assertChangedFromStock(method, label)
    val insns = method.instructions()
    val first = insns.getOrNull(0)
    val second = insns.getOrNull(1)
    assertTrue(
        first is NarrowLiteralInstruction &&
            first.opcode == Opcode.CONST_4 &&
            first.narrowLiteral.toInt() == if (expected) 1 else 0,
        "$label was not forced to $expected; first instruction is ${first?.opcode}\n$diagnostics"
    )
    assertTrue(
        second != null && second.opcode == Opcode.RETURN,
        "$label does not return immediately; second instruction is ${second?.opcode}\n$diagnostics"
    )
    assertTrue(
        (second as OneRegisterInstruction).registerA ==
            (first as OneRegisterInstruction).registerA,
        "$label returns a different register than the one set\n$diagnostics"
    )
}

fun assertReturnsInt(
    method: Method,
    expected: Int,
    label: String,
    diagnostics: String = "",
    requireChanged: Boolean = true,
) {
    if (requireChanged) assertChangedFromStock(method, label)
    val insns = method.instructions()
    val first = insns.getOrNull(0)
    assertTrue(
        first is NarrowLiteralInstruction &&
            first.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16) &&
            first.narrowLiteral == expected,
        "$label was not forced to $expected; first instruction is ${first?.opcode}\n$diagnostics"
    )
    assertTrue(
        insns.getOrNull(1)?.opcode == Opcode.RETURN,
        "$label does not return immediately; second instruction is ${insns.getOrNull(1)?.opcode}\n$diagnostics"
    )
    assertTrue(
        (insns[1] as OneRegisterInstruction).registerA == (first as OneRegisterInstruction).registerA,
        "$label returns a different register than the constant\n$diagnostics"
    )
}

fun assertReturnsMethodCall(
    method: Method,
    targetClass: String,
    targetName: String,
    label: String,
    diagnostics: String = "",
    requireChanged: Boolean = true,
) {
    if (requireChanged) assertChangedFromStock(method, label)
    val insns = method.instructions()
    val first = insns.getOrNull(0) as? ReferenceInstruction
    val ref =first?.reference as? MethodReference
    assertTrue(
        first?.opcode in setOf(
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
        ) &&
            ref != null && ref.definingClass == targetClass && ref.name == targetName,
        "$label does not call $targetClass.$targetName first; first instruction is ${first?.opcode} ${ref}\n$diagnostics"
    )
    assertTrue(
        insns.getOrNull(1)?.opcode == Opcode.MOVE_RESULT_OBJECT,
        "$label does not capture the call result; second instruction is ${insns.getOrNull(1)?.opcode}\n$diagnostics"
    )
    assertTrue(
        insns.getOrNull(2)?.opcode == Opcode.RETURN_OBJECT,
        "$label does not return the call result; third instruction is ${insns.getOrNull(2)?.opcode}\n$diagnostics"
    )
    assertTrue(
        (insns[2] as OneRegisterInstruction).registerA == (insns[1] as OneRegisterInstruction).registerA,
        "$label returns a different register than the call result\n$diagnostics"
    )
}

/** Asserts the method was replaced by an immediate `return-void`. */
fun assertReturnsEarlyVoid(
    method: Method,
    label: String,
    diagnostics: String = "",
    requireChanged: Boolean = true,
) {
    if (requireChanged) assertChangedFromStock(method, label)
    assertTrue(
        method.instructions().firstOrNull()?.opcode == Opcode.RETURN_VOID,
        "$label does not return immediately; first instruction is ${method.instructions().firstOrNull()?.opcode}\n$diagnostics"
    )
}

/** The unobfuscated MobileAds static voids that throw (or start the SDK) without initialize(). */
val GMA_THROWING_STATICS = listOf("initialize", "setAppVolume", "setAppMuted", "openDebugMenu", "setPlugin")

/**
 * AGENTS.md rule 9: every concrete static void on Google Mobile Ads' MobileAds returns at once
 * (each of [required] must be one of them) and putPublisherFirstPartyIdEnabled, when present,
 * answers false.
 */
fun assertMobileAdsStaticsNeutralised(mobileAds: ClassDef, required: List<String> = GMA_THROWING_STATICS) {
    val staticVoids = mobileAds.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.implementation != null && it.name != "<clinit>"
    }
    for (name in required) {
        assertTrue(staticVoids.any { it.name == name }, "${mobileAds.type} has no concrete static void $name")
    }
    assertTrue(staticVoids.isNotEmpty(), "${mobileAds.type} has no concrete static void")
    staticVoids.forEach {
        assertReturnsEarlyVoid(it, label = "MobileAds.${it.name}(${it.parameterTypes.joinToString()})")
    }
    mobileAds.methods.filter {
        it.name == "putPublisherFirstPartyIdEnabled" && it.returnType == "Z" && it.implementation != null
    }.forEach { assertForcedBoolean(it, expected = false, label = "MobileAds.putPublisherFirstPartyIdEnabled(Z)") }
}

/**
 * Asserts every concrete void method called one of [names] on [type] returns at once, and that
 * each name has at least one (an SDK hook that matched nothing must fail the test).
 */
fun assertAllReturnEarly(classes: List<ClassDef>, type: String, vararg names: String) {
    val owner = classes.firstOrNull { it.type == type } ?: error("$type not found in emitted dexes")
    for (name in names) {
        val targets = owner.methods.filter { it.name == name && it.returnType == "V" && it.implementation != null }
        assertTrue(targets.isNotEmpty(), "$type has no concrete void $name")
        targets.forEach { assertReturnsEarlyVoid(it, label = "$type->$name(${it.parameterTypes.joinToString()})") }
    }
}

private const val MAX_AD_VIEW_TYPE = "Lcom/applovin/mediation/ads/MaxAdView;"

/** The registers an invoke-* passes, in order, or null when [insn] is not an invoke. */
private fun invokeArguments(insn: Instruction): List<Int>? = when (insn) {
    is FiveRegisterInstruction ->
        listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG).take(insn.registerCount)
    is RegisterRangeInstruction -> (insn.startRegister until insn.startRegister + insn.registerCount).toList()
    else -> null
}

/** True for `const/4 reg, 0x8` and `const/16 reg, 0x8` (View.GONE) writing [register]. */
private fun isConstGoneInto(insn: Instruction?, register: Int): Boolean =
    insn != null && (insn.opcode == Opcode.CONST_4 || insn.opcode == Opcode.CONST_16) &&
        (insn as NarrowLiteralInstruction).narrowLiteral == 8 &&
        (insn as OneRegisterInstruction).registerA == register

private fun isSetVisibilityCall(insn: Instruction?, owner: String? = null): Boolean {
    if (insn == null || invokeArguments(insn) == null) return false
    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return ref.name == "setVisibility" && ref.returnType == "V" &&
        ref.parameterTypes.map(CharSequence::toString) == listOf("I") &&
        (owner == null || ref.definingClass == owner)
}

/** Register of `this` in an instance [method] (its first parameter register). */
private fun thisRegister(method: Method): Int {
    val paramRegisters = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    return method.implementation!!.registerCount - paramRegisters - 1
}

/**
 * AppLovin MAX banner views stay GONE (shared/misc/ads/MaxGmaAdHooks.kt hideMaxAdView):
 * setVisibility(I) first loads GONE (const/4 or const/16 of 0x8) into its argument p1, which is not
 * overwritten before the setVisibility(I)V call that receives (this, p1); and every constructor
 * return-void is preceded by a const/4|const/16 0x8 into the register that the
 * `invoke-virtual {this, reg}, MaxAdView->setVisibility(I)V` right before it passes.
 */
fun assertMaxAdViewHidden(classes: List<ClassDef>) {
    val view = classes.firstOrNull { it.type == MAX_AD_VIEW_TYPE } ?: error("$MAX_AD_VIEW_TYPE not found in emitted dexes")
    assertMaxAdViewHidden(view)
}

internal fun assertMaxAdViewHidden(view: ClassDef) {
    val type = view.type
    val setVisibility = view.method("setVisibility", listOf("I"))
    val body = setVisibility.instructions()
    val self = thisRegister(setVisibility)
    val p1 = self + 1
    val first = body.firstOrNull()
    assertTrue(
        isConstGoneInto(first, p1),
        "MaxAdView.setVisibility does not load GONE (const/4 or const/16 0x8) into its argument p1 first; first instruction is ${first?.opcode}"
    )
    val callIndex = body.indexOfFirst { isSetVisibilityCall(it) }
    assertTrue(callIndex > 0, "MaxAdView.setVisibility never calls setVisibility(I)V")
    assertTrue(
        invokeArguments(body[callIndex]) == listOf(self, p1),
        "MaxAdView.setVisibility passes ${invokeArguments(body[callIndex])}, not (this p$self, GONE register p$p1)"
    )
    for (i in 1 until callIndex) {
        assertTrue(
            !(body[i].opcode.setsRegister() && (body[i] as? OneRegisterInstruction)?.registerA == p1),
            "MaxAdView.setVisibility overwrites the GONE register p$p1 at $i (${body[i].opcode}) before the call"
        )
    }

    val constructors = view.methods.filter { it.name == "<init>" && it.implementation != null }
    assertTrue(constructors.isNotEmpty(), "MaxAdView has no constructor")
    for (constructor in constructors) {
        val insns = constructor.instructions()
        val returns = insns.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        assertTrue(returns.isNotEmpty(), "MaxAdView constructor has no return-void")
        val ctorThis = thisRegister(constructor)
        for (index in returns) {
            val call = insns.getOrNull(index - 1)
            val args = call?.let(::invokeArguments)
            val gone = args?.getOrNull(1)
            assertTrue(
                call != null && args != null &&
                    (call.opcode == Opcode.INVOKE_VIRTUAL || call.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                    isSetVisibilityCall(call, type) &&
                    args.size == 2 && args[0] == ctorThis && gone != null &&
                    isConstGoneInto(insns.getOrNull(index - 2), gone),
                "MaxAdView.<init>(${constructor.parameterTypes.joinToString()}) returns at $index without " +
                    "invoke-virtual {this, reg}, setVisibility(I)V right after const/4|const/16 reg, 0x8 into that same register"
            )
        }
    }
}

/** Asserts the method was replaced by `return FlowKt.flowOf(Boolean.TRUE)`. */
fun assertReturnsTrueFlow(method: Method, label: String, diagnostics: String = "", requireChanged: Boolean = true) {
    if (requireChanged) assertChangedFromStock(method, label)
    val insns = method.instructions()
    val first = insns.getOrNull(0) as? ReferenceInstruction
    val field = first?.reference as? FieldReference
    assertTrue(
        first?.opcode == Opcode.SGET_OBJECT &&
            field?.definingClass == "Ljava/lang/Boolean;" &&
            field.name == "TRUE",
        "$label does not start with Boolean.TRUE; first instruction is ${first?.opcode} ${field}\n$diagnostics"
    )
    val second = insns.getOrNull(1) as? ReferenceInstruction
    val call = second?.reference as? MethodReference
    assertTrue(
        second?.opcode == Opcode.INVOKE_STATIC &&
            call?.definingClass == "Lkotlinx/coroutines/flow/FlowKt;" &&
            call.name == "flowOf",
        "$label does not call FlowKt.flowOf; second instruction is ${second?.opcode} ${call}\n$diagnostics"
    )
    assertTrue(
        insns.getOrNull(2)?.opcode == Opcode.MOVE_RESULT_OBJECT,
        "$label does not move the flow result; third instruction is ${insns.getOrNull(2)?.opcode}\n$diagnostics"
    )
    assertTrue(
        insns.getOrNull(3)?.opcode == Opcode.RETURN_OBJECT,
        "$label does not return the flow; fourth instruction is ${insns.getOrNull(3)?.opcode}\n$diagnostics"
    )
}

/** Drops a trailing parenthetical note ("1.1.15 (versionCode 75)" -> "1.1.15") and surrounding space. */
private fun normaliseVersionName(name: String): String = name.replace(Regex("""\s*\(.*\)\s*$"""), "").trim()

/**
 * Fails when the fixture [apk] is not the app/version the test claims. The manifest comes from the
 * patcher's own decoded [metadata]; [version] (and [versionCode] when given) is what the test
 * passes, and the patches' Compatibility targets for [pkg] must list that same version
 * (a target with version = null accepts any).
 */
private fun assertFixtureMatches(
    apk: File,
    metadata: app.morphe.patcher.PackageMetadata,
    pkg: String,
    version: String,
    versionCode: Int?,
    patches: List<Patch>,
) {
    val where = "fixture ${apk.absolutePath}"
    val actual = metadata.versionName.orEmpty()
    assertTrue(
        metadata.packageName == pkg,
        "$where has package ${metadata.packageName}, but the test expects $pkg"
    )
    assertTrue(
        normaliseVersionName(actual) == normaliseVersionName(version),
        "$where has versionName '$actual' (versionCode ${metadata.versionCode}), but the test expects '$version'"
    )
    if (versionCode != null) {
        // Explicit override from the caller.
        assertTrue(
            metadata.versionCode == versionCode.toString(),
            "$where has versionCode ${metadata.versionCode}, but the test expects $versionCode"
        )
    }
    for (patch in patches) {
        val targets = patch.compatibility.orEmpty().filter { it.packageName == pkg }.flatMap { it.targets }
        val declared = targets.mapNotNull { it.version }.map(::normaliseVersionName)
        if (targets.isEmpty() || targets.any { it.version == null }) continue
        assertTrue(
            normaliseVersionName(actual) in declared,
            "$where has versionName '$actual', but patch '${patch.name}' declares compatibility targets $declared for $pkg"
        )
        // Same versionName is not enough: a different build can share it. Compare the manifest's
        // versionCode with the code(s) declared by the target(s) of that versionName (per-ABI
        // targets list several codes: any one is accepted). A target with no declared code cannot
        // be checked and is skipped.
        if (versionCode == null) {
            val sameName = targets.filter { it.version != null && normaliseVersionName(it.version!!) == normaliseVersionName(actual) }
            val codes = sameName.flatMap { it.versionCodes?.values.orEmpty() }.map { it.toString() }
            if (codes.isNotEmpty() && sameName.none { it.versionCodes.isNullOrEmpty() }) {
                assertTrue(
                    metadata.versionCode in codes,
                    "$where has versionCode ${metadata.versionCode} (versionName '$actual'), but patch '${patch.name}' " +
                        "declares versionCode(s) $codes for $pkg: an alternate build with the same versionName?"
                )
            }
        }
    }
}

/**
 * Applies [patchNames] (filtered to patches compatible with [pkg]) to [apk] and returns every
 * class of the patched APK, one ClassDef per type, in ART load order. Pass [filterByCompatibility] = false for universal patches
 * that declare no compatibility at all (they apply to any package).
 */
fun applyPatches(
    apk: File,
    workDir: File,
    pkg: String,
    version: String,
    patchNames: Set<String>,
    allPatches: Set<Patch>,
    filterByCompatibility: Boolean = true,
    versionCode: Int? = null,
    onPatchedApk: (File) -> Unit = {},
    // Patch name to (option key to value), set before the patches run (every other option keeps its default).
    options: Map<String, Map<String, Any?>> = emptyMap(),
): List<ClassDef> {
    val target = allPatches.filter { patch ->
        patch.name in patchNames &&
            (!filterByCompatibility || patch.compatibility.orEmpty().any { it.packageName == pkg })
    }.toList()
    for (patch in target) {
        options[patch.name]?.forEach { (key, value) -> patch.options.set(key, value) }
    }

    assertTrue(
        target.map { it.name }.toSet() == patchNames,
        "expected patches $patchNames in the bundle, found ${target.map { it.name }}"
    )

    // validate = true: every match()/matchOrNull() must match at most one method (as matchSingle()
    // would), and slow fingerprints and patches are logged. The patcher behaviours (exact strings,
    // on-request resource decoding, ...) follow the bundle manifest's Patcher-Version, as in
    // Manager and the CLI.
    val config = PatcherConfig(apk, workDir.resolve("out"), validate = true)

    // Patcher.invoke() only executes the patches. Patcher.get() finalizes the bytecode context
    // and emits the patched dex files - without it you just re-read the unpatched input dex.
    //
    // Since morphe-patcher 2.0.0 (default BytecodeMode.STRIP_FAST), PatcherResult.dexFiles is NOT
    // the app's class set: it holds only the original dex files a patch stripped classes from plus
    // new dex files (numbered after the originals) with the modified classes; original dex files
    // without a modified class are left out (PatcherResult.dexFilesComplete = false), and
    // ApkUtils.applyTo overlays dexFiles on the input APK by entry name. Reading dexFiles alone
    // drops every class of an untouched dex, so "nothing calls X" scans would pass vacuously.
    // Build the patched APK the way Manager does (Session.kt: resourcesApk, else a copy of the
    // input, then applyTo) and read its classes*.dex instead.
    val patchedApk = Patcher(config).use { patcher ->
        // The fixture store is shared (apks/<slug>/base.apk), so verify the APK really is the app and
        // version this test and the patches declare before anything is patched.
        assertFixtureMatches(apk, patcher.context.packageMetadata, pkg, version, versionCode, target)
        patcher += target.toSet()
        val results = runBlocking { patcher.invoke().toList() }
        val ours = results.filter { it.patch.name in patchNames }
        assertTrue(
            ours.size == patchNames.size,
            "every patch must execute exactly once; got ${results.map { it.patch.name }}"
        )
        for (r in ours) {
            assertNull(r.exception, "patch '${r.patch.name}' failed: ${r.exception}")
        }
        val result = patcher.get()
        assertTrue(result.dexFiles.isNotEmpty(), "patcher produced no patched dex files")
        val output = result.resources.resourcesApk
            ?: workDir.resolve("patched-unsigned.apk").also { copy ->
                Files.copy(apk.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        result.applyTo(output)
        output
    }

    onPatchedApk(patchedApk)
    val classes = patchedApkClasses(patchedApk)
    // No patch here removes a class, so every stock class must still be there; this fails if the
    // harness ever reads a partial dex set again.
    val types = classes.mapTo(HashSet()) { it.type }
    val stock = patchedApkClasses(apk)
    StockMethods.register(stock)
    val missing = stock.map { it.type }.filter { it !in types }
    assertTrue(
        missing.isEmpty(),
        "${missing.size} stock classes are missing from the patched APK, e.g. ${missing.take(5)}"
    )
    return classes
}

/**
 * Every class of the dex files ART loads from [apk] (root `classes*.dex`, in load order), read
 * from memory (path-backed dex files stay locked on Windows). Fails if a type is defined twice,
 * so a lookup can never land on a stale copy of a patched class.
 */
fun patchedApkClasses(apk: File): List<ClassDef> {
    val dexEntry = Regex("""classes(\d*)\.dex""")
    val perDex = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .mapNotNull { entry -> dexEntry.matchEntire(entry.name)?.let { entry to it } }
            .sortedBy { (_, match) -> match.groupValues[1].toIntOrNull() ?: 1 }
            .map { (entry, _) ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                entry.name to DexBackedDexFile(Opcodes.forApi(36), ByteBuffer.wrap(bytes)).classes.toList()
            }
            .toList()
    }
    val classes = perDex.flatMap { it.second }
    val duplicates = perDex.flatMap { (name, defs) -> defs.map { it.type to name } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
    assertTrue(
        duplicates.isEmpty(),
        "${duplicates.size} types are defined more than once in ${apk.name}, e.g. ${duplicates.entries.take(5)}"
    )
    return classes
}

/**
 * Picker icons of a Heval Controls list setting (2026-11-03): [init] hands [provider]'s INSTANCE to
 * HevalSettings.setOptionIconProvider, [provider] implements HevalSettings.OptionIconProvider (or,
 * with [images], OptionImageProvider, which extends it), and the core (OptionIcons,
 * HevalSettings.optionIcon / optionImageUrl), the picker that draws the icons and, with [images],
 * the image loader were merged.
 */
fun assertOptionIconsWired(byType: Map<String, ClassDef>, init: Method, provider: String, images: Boolean = false) {
    val settings = "Lapp/template/extension/hevalcontrols/HevalSettings;"
    val iconInterface = "Lapp/template/extension/hevalcontrols/HevalSettings\$OptionIconProvider;"
    val imageInterface = "Lapp/template/extension/hevalcontrols/HevalSettings\$OptionImageProvider;"
    val providerInterface = if (images) imageInterface else iconInterface
    val icons = byType[provider] ?: error("$provider not found in the emitted dexes")
    assertTrue(providerInterface in icons.interfaces, "$provider does not implement $providerInterface")
    if (images) {
        val image = byType[imageInterface] ?: error("$imageInterface not found in the emitted dexes")
        assertTrue(iconInterface in image.interfaces, "$imageInterface does not extend $iconInterface")
        assertTrue(icons.methods.any { it.name == "imageUrlFor" && it.implementation != null },
            "$provider has no imageUrlFor")
    }
    assertTrue(byType.containsKey("Lapp/template/extension/hevalcontrols/OptionIcons;"), "OptionIcons was not merged")
    fun Instruction.call() = ((this as? ReferenceInstruction)?.reference as? MethodReference)
    val insns = init.instructions()
    val register = insns.indexOfFirst {
        it.call()?.let { r -> r.definingClass == settings && r.name == "setOptionIconProvider" } == true
    }
    assertTrue(register > 0, "${init.definingClass}->${init.name} does not call HevalSettings.setOptionIconProvider")
    assertTrue(insns.subList(0, register).any {
        it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).definingClass == provider
    }, "${init.definingClass}->${init.name} does not pass $provider's instance")
    val picker = byType.filterKeys { it.startsWith("Lapp/template/extension/hevalcontrols/ui/ListPicker") }.values
    val pickerCalls = picker.flatMap { it.methods }.flatMap { it.instructions() }.mapNotNull { it.call() }
    assertTrue(pickerCalls.any { it.definingClass == settings && it.name == "optionIcon" },
        "ListPicker does not ask HevalSettings.optionIcon")
    if (images) {
        assertTrue(pickerCalls.any { it.definingClass == settings && it.name == "optionImageUrl" },
            "ListPicker does not ask HevalSettings.optionImageUrl")
        val loader = "Lapp/template/extension/hevalcontrols/ui/OptionImageLoader;"
        assertTrue(byType.containsKey(loader), "$loader was not merged")
        assertTrue(pickerCalls.any { it.definingClass == loader && it.name == "bind" },
            "ListPicker does not hand the image URL to OptionImageLoader.bind")
    }
}

