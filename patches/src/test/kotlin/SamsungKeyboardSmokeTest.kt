import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PKG = "com.samsung.android.honeyboard"
private const val NEW_PKG = "app.heval.honeyboard"
private const val EXT = "Lapp/template/extension/samsungkeyboard/"
private const val EXT_PREFIX = "Lapp/template/extension/"
private const val SERVICE = "Lcom/samsung/android/honeyboard/service/HoneyBoardService;"
private const val SCPM_RECEIVER = "Lcom/samsung/android/honeyboard/base/broadcastreceiver/ScpmReceiver;"
private const val GSIM_LOGGER = "Lcom/samsung/android/sdk/handwriting/GSIMLogger;"
private const val CSC_GIF = "CscFeature_Sip_ConfigWhetherToProvideGif"
private const val STRING = "Ljava/lang/String;"
private const val CLIP = "Lcom/samsung/android/honeyboard/icecone/clipboard/data/entity/Clip;"
private const val CLIPBOARD_VIEW_MODEL = "Lcom/samsung/android/honeyboard/icecone/clipboard/viewmodel/ClipboardViewModel;"
private const val FLOATING_CURSOR = "SEC_FLOATING_FEATURE_SIP_SUPPORT_CURSOR_CONTROL"
private const val FLOATING_ONEHAND = "SEC_FLOATING_FEATURE_COMMON_CONFIG_ONEHAND_MODE_POLICY"

private val ALL_PATCHES = setOf(
    "Change package name",
    "Enable non-One UI ROM support",
    "Change app name",
    "Disable telemetry",
    "Always incognito",
    "Stop remote updates",
    "No GIF tracking",
    "Remove internet permission",
    "Show GIFs in every region",
    "Always use GIPHY",
    "Bigger clipboard history",
    "Clipboard columns",
    "Swipe cursor control",
    "One-handed keyboard",
    "Restore key vibration",
    "Toolbar item count",
    "Autofill in any field",
)

class SamsungKeyboardSmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun Instruction.str() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
    private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
    private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
    private fun Method.strings() = instructions().mapNotNull { it.str() }
    private fun Method.params() = parameterTypes.map(CharSequence::toString)

    private fun List<ClassDef>.methodsWhere(predicate: (Method) -> Boolean) =
        flatMap { c -> c.methods.filter { it.implementation != null && predicate(it) } }

    private fun xml(text: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(text.byteInputStream()).documentElement

    private fun Element.all(tag: String) = getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    @Test
    fun `Samsung Keyboard patches emit the ported bytecode, manifest and resources`() {
        val root = repoRoot()
        val apk = File(root, "apks/samsungkeyboard/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "5.9.40.75",
            versionCode = 594075300,
            patchNames = ALL_PATCHES,
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val byType = classes.associateBy { it.type }
        // The app's own classes: those the stock APK already had (the extension and its stand-ins are new).
        val stock = patchedApkClasses(apk).mapTo(HashSet()) { it.type }
        val app = classes.filter { it.type in stock }

        // --- The extension is merged: its nine logic classes and the One UI stand-ins ---
        for (name in listOf("Downloads", "Fallbacks", "Gifs", "Java21", "LocalClipboard", "OneUi", "Sem", "Services", "SystemAccess")) {
            assertTrue("$EXT$name;" in byType, "extension class $name is missing")
        }
        for (standIn in listOf("Landroid/os/SemSystemProperties;", "Lcom/samsung/android/feature/SemCscFeature;")) {
            assertTrue(standIn in byType && standIn !in stock, "stand-in $standIn is not merged")
        }

        // --- SystemAccess redirects: the app's Settings / Context / Build / WindowManager calls go to the shims ---
        val invokes = app.flatMap { c -> c.methods.flatMap { m -> m.instructions().mapNotNull { it.call() } } }
        val systemAccess = invokes.filter { it.definingClass == "${EXT}SystemAccess;" }
        assertTrue(systemAccess.size >= 300, "only ${systemAccess.size} calls go through SystemAccess")
        assertTrue(systemAccess.any { it.name == "fieldSet" }, "the input-features reflective write does not go through SystemAccess.fieldSet")
        assertTrue(systemAccess.any { it.name.startsWith("secure") } && systemAccess.any { it.name.startsWith("context") },
            "SystemAccess has no Settings.Secure / Context redirect")

        // --- sem* members go to the generated Sem shims, and no direct One UI call is left in the app ---
        val shims = invokes.filter { it.definingClass == "${EXT}Sem;" }
        assertTrue(shims.size >= 100, "only ${shims.size} calls go through the generated Sem shims")
        val directSem = invokes.filter { it.definingClass.startsWith("Landroid/") && Regex("^sem[A-Z]").containsMatchIn(it.name) }
        assertTrue(directSem.isEmpty(), "direct One UI calls left: ${directSem.take(5).map { it.key() }}")
        val sem = byType.getValue("${EXT}Sem;")
        val generated = sem.methods.filter { it.name.matches(Regex("m\\d+")) }
        assertTrue(generated.size >= 50, "Sem has only ${generated.size} generated shims")
        for (shim in generated) {
            val code = shim.instructions()
            assertTrue(
                code.any { it.call()?.definingClass == "${EXT}OneUi;" },
                "Sem.${shim.name} does not call OneUi",
            )
        }
        // Resources.getIdentifier goes through OneUi, and Java 21 list calls through Java21.
        assertTrue(invokes.any { it.definingClass == "${EXT}OneUi;" && it.name == "getIdentifier" }, "getIdentifier lookups are not redirected")
        assertTrue(invokes.none { it.definingClass == "Ljava/util/stream/Stream;" && it.name == "toList" }, "Stream.toList calls are left")
        // The settings colour lookup names the stock package; OneUi.getIdentifier maps it to the renamed one.
        val colorLookup = byType.getValue("Lcom/samsung/android/honeyboard/support/color/OpenThemeColor;")
            .method("getColorWithResourceName", listOf("Landroid/content/Context;", STRING))
        assertTrue(
            colorLookup.instructions().any { it.call()?.let { c -> c.definingClass == "${EXT}OneUi;" && c.name == "getIdentifier" } == true },
            "OpenThemeColor.getColorWithResourceName does not call OneUi.getIdentifier",
        )

        // --- HoneyBoardService.onCreate starts OneUi.keyboardCreated ---
        val onCreate = byType.getValue(SERVICE).method("onCreate", emptyList())
        val first = onCreate.instructions().first().call()
        assertTrue(
            first != null && first.definingClass == "${EXT}OneUi;" && first.name == "keyboardCreated",
            "HoneyBoardService.onCreate does not call OneUi.keyboardCreated first: $first",
        )

        // --- Galaxy Store sticker link: the click handler found by its URL and shape goes through the safe starter ---
        val stickerClicks = app.methodsWhere { m ->
            m.name == "onClick" && m.params() == listOf("Landroid/view/View;") &&
                "samsungapps://CategoryList/0000005276" in m.strings()
        }
        assertEquals(1, stickerClicks.size, "expected one sticker store click handler, got ${stickerClicks.size}")
        val stickerCalls = stickerClicks.single().instructions().mapNotNull { it.call() }
        assertTrue(stickerCalls.any { it.definingClass == "${EXT}SystemAccess;" && it.name == "startActivitySafely" },
            "the sticker store click does not call SystemAccess.startActivitySafely")
        assertTrue(stickerCalls.none { it.name == "startActivity" }, "the sticker store click still calls startActivity directly")

        // --- Clipboard caller check and provider signature checks return early for the keyboard itself ---
        fun assertEarlySelfCheck(method: Method, helper: String, label: String) {
            val code = method.instructions()
            val call = code[0].call()
            assertTrue(
                call != null && call.definingClass == "${EXT}LocalClipboard;" && call.name == helper,
                "$label does not call LocalClipboard.$helper first: $call",
            )
            assertEquals(Opcode.MOVE_RESULT, code[1].opcode, "$label does not keep the helper's answer")
            assertEquals(Opcode.IF_EQZ, code[2].opcode, "$label has no branch on the helper's answer")
            assertEquals(Opcode.RETURN, code[3].opcode, "$label does not return early when the helper says yes")
            assertEquals(
                (code[1] as OneRegisterInstruction).registerA, (code[3] as OneRegisterInstruction).registerA,
                "$label returns a different register than the helper's answer",
            )
        }
        val callerChecks = app.methodsWhere {
            it.returnType == "Z" && it.params() == listOf(STRING) && ") can't access to clipboard." in it.strings()
        }
        assertTrue(callerChecks.isNotEmpty(), "clipboard caller check not found")
        callerChecks.forEach { assertEarlySelfCheck(it, "isSelfPackage", "clipboard caller check ${it.definingClass}.${it.name}") }
        val signatureChecks = app.methodsWhere {
            it.returnType == "Z" && it.params() == listOf("Landroid/content/Context;", STRING) &&
                "certification failed" in it.strings() && "valid signature : " in it.strings()
        }
        assertTrue(signatureChecks.isNotEmpty(), "provider signature check not found")
        signatureChecks.forEach { assertEarlySelfCheck(it, "isSelf", "signature check ${it.definingClass}.${it.name}") }

        // --- Disable telemetry ---
        val analytics = app.methodsWhere { it.returnType == "Z" && "Tracker is not initialized, status : " in it.strings() }
        assertTrue(analytics.isNotEmpty(), "Samsung Analytics readiness check not found")
        analytics.forEach { assertForcedBoolean(it, false, "Samsung Analytics check ${it.definingClass}.${it.name}") }
        val consent = app.methodsWhere {
            it.returnType == "Z" && it.params().isEmpty() && "canSyncNow=false isSamsungAccountLoggedIn=" in it.strings()
        }
        consent.forEach { assertForcedBoolean(it, false, "Galaxy AI consent sync ${it.definingClass}.${it.name}") }
        val typedText = app.methodsWhere {
            it.returnType == "V" && "[[AiWriter]_DATA_COLLECTION]" in it.strings() && "send data : " in it.strings()
        }
        typedText.forEach { assertReturnsEarlyVoid(it, "typed-text collection ${it.definingClass}.${it.name}") }
        assertReturnsEarlyVoid(byType.getValue(GSIM_LOGGER).method("insertLogData"), "GSIMLogger.insertLogData")
        val versionChecks = app.methodsWhere {
            it.returnType == "I" && it.params() == listOf("Landroid/content/Context;") && "DMA Client is not exist" in it.strings()
        }
        versionChecks.forEach { assertReturnsInt(it, 0, "DiagMon version check ${it.definingClass}.${it.name}") }
        // No lookup of the diagnostics agent's package is left: its name is blanked before getPackageInfo.
        for (method in app.methodsWhere { m -> m.instructions().any { it.str() == "com.sec.android.diagmonagent" } }) {
            val code = method.instructions()
            for (index in code.indices.filter { code[it].str() == "com.sec.android.diagmonagent" }) {
                val next = code.drop(index + 1).firstNotNullOfOrNull { it.call() }
                assertFalse(
                    next?.definingClass == "Landroid/content/pm/PackageManager;" && next.name == "getPackageInfo",
                    "${method.definingClass}.${method.name} still looks up the diagnostics agent",
                )
            }
        }

        // --- Stop remote updates ---
        assertReturnsEarlyVoid(byType.getValue(SCPM_RECEIVER).method("onReceive"), "ScpmReceiver.onReceive")

        // --- No GIF tracking: GIPHY pingbacks and Tenor register return at once ---
        fun assertReturnsNothing(method: Method, label: String) {
            val code = method.instructions()
            assertTrue(
                code[0].opcode == Opcode.RETURN_VOID ||
                    (code[0].opcode in setOf(Opcode.CONST_4, Opcode.CONST_WIDE_16) &&
                        code[1].opcode in setOf(Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE)),
                "$label does not return at once; it starts with ${code[0].opcode}, ${code[1].opcode}",
            )
        }
        val giphyPingbacks = app.methodsWhere { "api_key" in it.strings() && "pingback_id" in it.strings() }
        assertTrue(giphyPingbacks.isNotEmpty(), "GIPHY pingback sender not found")
        giphyPingbacks.forEach { assertReturnsNothing(it, "GIPHY pingback ${it.definingClass}.${it.name}") }
        val tenor = app.methodsWhere { "GRS_TenorRegisterManager" in it.strings() && "url is null" in it.strings() }
        assertTrue(tenor.isNotEmpty(), "Tenor register not found")
        tenor.forEach { assertReturnsNothing(it, "Tenor register ${it.definingClass}.${it.name}") }

        // --- Always incognito: exactly one boolean getter now starts with `return true` where the stock one did not ---
        fun Method.returnsTrueAtOnce(): Boolean {
            val code = instructions()
            val literal = code.getOrNull(0) as? com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
            return returnType == "Z" && params().isEmpty() && literal != null && literal.opcode == Opcode.CONST_4 &&
                literal.narrowLiteral == 1 && code.getOrNull(1)?.opcode == Opcode.RETURN
        }
        val stockByType = patchedApkClasses(apk).associateBy { it.type }
        val incognito = app.methodsWhere { m ->
            m.returnsTrueAtOnce() && stockByType.getValue(m.definingClass).methods.none {
                it.name == m.name && it.returnType == m.returnType && it.params() == m.params() && it.returnsTrueAtOnce()
            }
        }
        assertEquals(1, incognito.size, "incognito check candidates: ${incognito.map { "${it.definingClass}.${it.name}" }}")
        val flagTests = incognito.single().let { m ->
            m.instructions().any { (it as? WideLiteralInstruction)?.wideLiteral == 0x1000000L } ||
                m.instructions().any { it.call()?.returnType == "Z" }
        }
        assertTrue(flagTests, "the incognito check no longer reads the no-personalized-learning flag or a boolean test")
        // --- Always use GIPHY: every int return of the provider choice goes through Gifs.giphyFirst ---
        val choices = app.methodsWhere { it.returnType == "I" && it.params() == listOf(STRING) && "getGifCp " in it.strings() }
        assertEquals(1, choices.size, "GIF provider choice candidates: ${choices.map { it.name }}")
        val choice = choices.single().instructions()
        val returns = choice.indices.filter { choice[it].opcode == Opcode.RETURN }
        assertTrue(returns.isNotEmpty(), "provider choice has no int return")
        for (index in returns) {
            val register = (choice[index] as OneRegisterInstruction).registerA
            val call = choice[index - 2].call()
            assertTrue(
                call != null && call.definingClass == "${EXT}Gifs;" && call.name == "giphyFirst" &&
                    choice[index - 1].opcode == Opcode.MOVE_RESULT && (choice[index - 1] as OneRegisterInstruction).registerA == register,
                "int return at $index of the provider choice does not go through Gifs.giphyFirst",
            )
        }
        // The copies R8 made (notices) name both providers: their language is forced to "en" after the cut.
        val notices = app.methodsWhere { m -> "getGifCp " in m.strings() && !(m.returnType == "I" && m.params() == listOf(STRING)) }
        for (notice in notices) {
            val code = notice.instructions()
            val cut = code.indices.single { code[it].call()?.name == "substring" && code[it].call()?.definingClass == STRING }
            assertEquals("en", code[cut + 2].str(), "notice ${notice.definingClass}.${notice.name} does not force the language to en")
        }

        // --- Show GIFs in every region: the CSC switch read answers "enable" ---
        val cscReaders = app.methodsWhere { CSC_GIF in it.strings() }
        assertTrue(cscReaders.isNotEmpty(), "the GIF region switch reader not found")
        for (reader in cscReaders) {
            val code = reader.instructions()
            for (key in code.indices.filter { code[it].str() == CSC_GIF }) {
                val read = (key + 1 until code.size).first { code[it].call()?.name == "getString" }
                assertEquals(Opcode.MOVE_RESULT_OBJECT, code[read + 1].opcode, "CSC answer is not kept")
                assertEquals("enable", code[read + 2].str(), "CSC GIF switch ${reader.definingClass}.${reader.name} does not answer enable")
            }
        }

        // --- Bigger clipboard history: the cap is stored as 200, and an empty unpinned list leaves the enforcer ---
        fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
        val enforcers = app.methodsWhere { m ->
            m.returnType == "V" && m.params().let { it.size == 2 && it[0].startsWith("L") && it[1] == CLIP } && m.name != "<clinit>" && run {
                val names = m.instructions().mapNotNull { it.call() }.filter { it.definingClass == CLIP }.map { it.name }
                names.containsAll(listOf("getOrigin", "getUserId", "getPinned"))
            }
        }
        assertEquals(1, enforcers.size, "clipboard enforcer candidates: ${enforcers.map { "${it.definingClass}.${it.name}" }}")
        val enforcer = enforcers.single()
        val enforcerCode = enforcer.instructions()
        val capField = enforcerCode.firstNotNullOf { ins ->
            ins.field()?.takeIf { ins.opcode == Opcode.SGET && it.type == "I" && it.definingClass != enforcer.definingClass }
        }
        val capClinit = byType.getValue(capField.definingClass).method("<clinit>", emptyList()).instructions()
        val capStores = capClinit.indices.filter { capClinit[it].opcode == Opcode.SPUT && capClinit[it].field()?.name == capField.name }
        assertEquals(1, capStores.size, "stores to the history cap")
        val capConst = capClinit[capStores.single() - 1]
        assertEquals(Opcode.CONST, capConst.opcode, "the cap store is not preceded by a const")
        assertEquals(200, (capConst as NarrowLiteralInstruction).narrowLiteral, "history cap")
        assertEquals(
            (capClinit[capStores.single()] as OneRegisterInstruction).registerA, (capConst as OneRegisterInstruction).registerA,
            "the cap const is in another register than the store",
        )
        val getAt = enforcerCode.indexOfFirst { it.call()?.let { c -> c.definingClass == "Ljava/util/ArrayList;" && c.name == "get" } == true }
        val isEmptyCall = enforcerCode[getAt - 3].call()
        assertTrue(
            isEmptyCall != null && isEmptyCall.definingClass == "Ljava/util/ArrayList;" && isEmptyCall.name == "isEmpty",
            "no isEmpty guard before ArrayList.get in the enforcer: $isEmptyCall",
        )
        assertEquals(Opcode.MOVE_RESULT, enforcerCode[getAt - 2].opcode)
        assertEquals(Opcode.IF_NEZ, enforcerCode[getAt - 1].opcode)
        assertEquals(
            (enforcerCode[getAt] as? FiveRegisterInstruction)?.registerC,
            (enforcerCode[getAt - 3] as? RegisterRangeInstruction)?.startRegister,
            "the guard tests another list than ArrayList.get reads",
        )

        // --- Clipboard columns (enabled in this test; default 3) ---
        val colCounts = app.methodsWhere { it.definingClass == CLIPBOARD_VIEW_MODEL && it.name == "getColCount" }
        assertEquals(1, colCounts.size)
        assertReturnsInt(colCounts.single(), 3, "ClipboardViewModel.getColCount")

        // --- Swipe cursor control, one-handed keyboard, key vibration: each flag store gets `const/4 <same reg>, 0x1` ---
        val featuresClinits = app.methodsWhere { m ->
            m.name == "<clinit>" && FLOATING_CURSOR in m.strings() && FLOATING_ONEHAND in m.strings()
        }
        assertEquals(1, featuresClinits.size, "floating features initializer candidates")
        val features = featuresClinits.single()
        val featureCode = features.instructions()
        fun assertForcedTrueStore(store: Int, label: String) {
            assertEquals(Opcode.SPUT_BOOLEAN, featureCode[store].opcode, "$label is not a boolean store")
            val before = featureCode[store - 1]
            assertEquals(Opcode.CONST_4, before.opcode, "$label is not preceded by const/4")
            assertEquals(1, (before as NarrowLiteralInstruction).narrowLiteral, "$label is not preceded by const/4 0x1")
            assertEquals(
                (featureCode[store] as OneRegisterInstruction).registerA, (before as OneRegisterInstruction).registerA,
                "$label const is in another register than the store",
            )
        }
        for ((key, window) in listOf(FLOATING_CURSOR to 8, FLOATING_ONEHAND to 16)) {
            val at = featureCode.indexOfFirst { it.str() == key }
            val store = (at + 1..at + window).first { featureCode[it].opcode == Opcode.SPUT_BOOLEAN }
            assertForcedTrueStore(store, key)
        }
        val gates = app.methodsWhere { it.returnType == "V" && it.params() == listOf("I") && "vib is off" in it.strings() }
        assertEquals(1, gates.size, "key vibration gate candidates")
        val vibFlag = gates.single().instructions().firstNotNullOf { ins -> ins.field()?.takeIf { ins.opcode == Opcode.SGET_BOOLEAN } }
        assertEquals(features.definingClass, vibFlag.definingClass, "the vibration flag is not set by the features initializer")
        val vibStores = featureCode.indices.filter { featureCode[it].opcode == Opcode.SPUT_BOOLEAN && featureCode[it].field()?.name == vibFlag.name }
        assertEquals(1, vibStores.size, "stores to the vibration flag")
        assertForcedTrueStore(vibStores.single(), "key vibration flag")

        // --- Toolbar item count (enabled in this test; default 8): every 7/8 of the cap method is const/16 8 ---
        val toolbarLoader = app.methodsWhere { "loadUserSet" in it.strings() && "edit_toolbar" in it.strings() && it.returnType == "V" }
        assertEquals(1, toolbarLoader.size, "toolbar loader candidates")
        val loaderCode = toolbarLoader.single().instructions()
        val loaderTypes = loaderCode.mapNotNull { it.field() }.filter { it.definingClass == toolbarLoader.single().definingClass }.map { it.type }.toSet()
        val loaderCalls = loaderCode.mapNotNull { it.call() }.filter { it.returnType == "I" && it.parameterTypes.isEmpty() }.map { it.name }.toSet()
        val caps = app.methodsWhere { m ->
            m.definingClass in loaderTypes && m.name in loaderCalls &&
                m.returnType == "I" && m.params().isEmpty() && (m.accessFlags and 0x11) == 0x11 &&
                m.instructions().let { code ->
                    code.any { it.opcode == Opcode.PACKED_SWITCH } &&
                        code.any { it.opcode == Opcode.CONST_16 && (it as NarrowLiteralInstruction).narrowLiteral == 8 } &&
                        code.any { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 4 }
                }
        }
        assertEquals(1, caps.size, "toolbar cap candidates: ${caps.map { "${it.definingClass}.${it.name}" }}")
        val capCode = caps.single().instructions()
        val smallConsts = capCode.filter { it.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) }
            .map { it as NarrowLiteralInstruction }
        assertTrue(smallConsts.none { it.narrowLiteral == 7 }, "a const 7 is left in the toolbar cap")
        assertTrue(smallConsts.none { it.opcode != Opcode.CONST_16 && it.narrowLiteral == 8 }, "a short const 8 is left in the toolbar cap")
        assertTrue(smallConsts.count { it.opcode == Opcode.CONST_16 && it.narrowLiteral == 8 } >= 5, "fewer than five const/16 8 in the toolbar cap")
        assertTrue(smallConsts.any { it.opcode == Opcode.CONST_4 && it.narrowLiteral == 4 }, "the small-mode 4 is gone from the toolbar cap")
        // The loader that reads the cap through the interface still finds a method of that name.
        assertTrue(
            loaderCode.any { it.opcode == Opcode.INVOKE_INTERFACE && it.call()?.name == caps.single().name && it.call()?.returnType == "I" },
            "the toolbar loader does not call the cap by name",
        )

        // --- Autofill in any field: the text length reads 0 right after its move-result, and the branch tests that register ---
        val inlineSuggestions = app.methodsWhere {
            it.returnType == "V" && it.params() == listOf("Landroid/view/inputmethod/InlineSuggestionsResponse;") &&
                "Cursor is not at first position, skipping suggestions" in it.strings()
        }
        assertEquals(1, inlineSuggestions.size, "inline suggestions candidates")
        val inlineCode = inlineSuggestions.single().instructions()
        val lengthAt = inlineCode.indexOfFirst { it.call()?.let { c -> c.definingClass == "Ljava/lang/CharSequence;" && c.name == "length" } == true }
        assertTrue(lengthAt >= 0, "no CharSequence.length() in the inline suggestions method")
        assertEquals(Opcode.MOVE_RESULT, inlineCode[lengthAt + 1].opcode)
        val lengthRegister = (inlineCode[lengthAt + 1] as OneRegisterInstruction).registerA
        val zero = inlineCode[lengthAt + 2]
        assertEquals(Opcode.CONST_4, zero.opcode, "no const/4 after the length() result")
        assertEquals(0, (zero as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(lengthRegister, (zero as OneRegisterInstruction).registerA, "the zero goes into another register than the length")
        val lez = inlineCode[lengthAt + 3]
        assertEquals(Opcode.IF_LEZ, lez.opcode, "the length branch does not follow the zero")
        assertEquals(lengthRegister, (lez as OneRegisterInstruction).registerA, "the branch tests another register than the length")
        assertTrue((lengthAt + 4..lengthAt + 6).any { inlineCode[it].str() == "Cursor is not at first position, skipping suggestions" }, "the skip log does not follow the branch")

        // --- Change package name: the authority and permission literals follow the manifest ---
        val manifestText = workDir.walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }
            .map { it.readText() }.firstOrNull { it.contains("package=\"$NEW_PKG\"") }
            ?: error("no decoded AndroidManifest.xml of the renamed package under $workDir")
        val manifest = xml(manifestText)
        assertEquals(NEW_PKG, manifest.getAttribute("package"))
        assertFalse(manifest.hasAttribute("android:sharedUserId"), "sharedUserId is still in the manifest")
        assertFalse(manifest.hasAttribute("coreApp"), "coreApp is still in the manifest")
        val application = manifest.all("application").single()
        val authorities = manifest.all("provider").filter { it.parentNode == application }
            .flatMap { it.getAttribute("android:authorities").split(';') }
        assertTrue(authorities.size >= 17, "only ${authorities.size} provider authorities")
        assertTrue(authorities.all { it.contains(NEW_PKG) },
            "an authority was not moved: ${authorities.filterNot { it.contains(NEW_PKG) }}")
        val permissions = manifest.all("permission").map { it.getAttribute("android:name") }
        assertTrue(permissions.size >= 11 && permissions.all { it.contains(NEW_PKG) }, "permissions not moved: $permissions")
        val literals = app.flatMap { c -> c.methods.flatMap { m -> m.instructions().mapNotNull { it.str() } } }.toSet()
        assertTrue(authorities.count { it in literals } >= 3, "few bare authority literals follow the manifest: ${authorities.filter { it in literals }}")
        val movedUris = literals.filter { it.startsWith("content://$NEW_PKG.") }
        assertTrue(movedUris.size >= 15, "few content:// literals follow the manifest: $movedUris")
        val oldAuthorities = authorities.filter { it.startsWith("$NEW_PKG.") }.map { PKG + it.removePrefix(NEW_PKG) }
        assertTrue(oldAuthorities.none { it in literals }, "old authority literals left: ${oldAuthorities.filter { it in literals }}")
        assertTrue(literals.none { it.startsWith("content://$PKG.") }, "old content:// authority literals left")

        // --- Remove internet permission, optional libraries, app name ---
        assertTrue(
            manifest.all("uses-permission").none { it.getAttribute("android:name") == "android.permission.INTERNET" },
            "INTERNET permission is still declared",
        )
        val libraries = manifest.all("uses-library") + manifest.all("uses-native-library")
        assertTrue(libraries.isNotEmpty() && libraries.all { it.getAttribute("android:required") == "false" }, "a library is still required")
        val labels = manifest.all("*").map { it.getAttribute("android:label") }.filter { it.isNotEmpty() }
        assertTrue("@string/app_name" !in labels && "@string/patched_keyboard_name" in labels, "manifest labels not repointed: $labels")
        val strings = workDir.walkTopDown().filter { it.isFile && it.name == "strings.xml" && it.parentFile.name == "values" }
            .map { it.readText() }.firstOrNull { it.contains("patched_keyboard_name") }
            ?: error("no decoded res/values/strings.xml with patched_keyboard_name under $workDir")
        assertTrue(strings.contains(">Samsung Keyboard (Heval)<"), "app name string is not the default")

        // --- SPR icons are converted: no SPR file is left in the decoded drawables ---
        val drawables = workDir.walkTopDown().filter { it.isFile && it.parentFile.name.startsWith("drawable") }.toList()
        assertTrue(drawables.any { it.name.endsWith(".xml") && it.readText().contains("<vector") }, "no converted vector drawable found")
        val spr = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte(), 0)
        assertTrue(drawables.none { f -> f.length() >= 4 && f.inputStream().use { it.readNBytes(4) }.contentEquals(spr) }, "an SPR drawable is left")
    }
}
