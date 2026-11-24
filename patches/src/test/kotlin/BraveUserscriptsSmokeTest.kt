import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

private const val EXT = "Lapp/template/extension/bravemonkey/BraveMonkey;"
private const val EXT_PREFIX = "Lapp/template/extension/bravemonkey/"
private const val TAB_IMPL = "Lorg/chromium/chrome/browser/tab/TabImpl;"

class BraveUserscriptsSmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
    private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference
    private fun Instruction.hasString(value: String) =
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == value

    private fun List<ClassDef>.hostMethods(): List<Method> =
        filterNot { it.type.startsWith(EXT_PREFIX) }.flatMap { it.methods }

    /** The two ids and the menu builder are found the way the patch finds them. */
    private fun menuBuilder(methods: List<Method>): Method = methods.single { method ->
        method.returnType.startsWith("L") && method.parameterTypes.isEmpty() &&
            method.instructions().any { it.hasString("add_to_playlist_button") } &&
            method.instructions().any { it.hasString("customizable_brave_menu_item_id_") }
    }

    private fun containsUtf16(bytes: ByteArray, text: String) =
        String(bytes, Charsets.ISO_8859_1).contains(String(text.toByteArray(Charsets.UTF_16LE), Charsets.ISO_8859_1))

    private fun containsUtf8(bytes: ByteArray, text: String) =
        String(bytes, Charsets.ISO_8859_1).contains(String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))

    @Test
    fun `Userscripts hooks the app start, the page load, the menu and its click`() {
        val root = repoRoot()
        val apk = File(root, "apks/brave/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        var patchedApk: File? = null
        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = "com.brave.browser",
            version = "1.100.58",
            patchNames = setOf("Userscripts"),
            allPatches = loadAllPatches(newestPatchBundle(root)),
            onPatchedApk = { patchedApk = File(workDir, "kept.apk").also { copy -> it.copyTo(copy, overwrite = true) } },
        )
        val methods = classes.hostMethods()
        val stock = patchedApkClasses(apk).hostMethods()

        // 1. App start: install is the first instruction of SplitChromeApplication.onCreate.
        val onCreate = classes.single { it.type == "Lorg/chromium/chrome/browser/base/SplitChromeApplication;" }
            .method("onCreate", emptyList())
        val first = onCreate.instructions().first()
        assertTrue(
            first.opcode == Opcode.INVOKE_STATIC && first.methodRef()?.definingClass == EXT &&
                first.methodRef()?.name == "install",
            "onCreate does not start with BraveMonkey.install; starts with ${first.opcode}",
        )

        // 2. Page loaded: one host method calls onPageLoad, the (Page, L, GURL, Z, I)V callback, and
        // passes the register it just loaded the TabImpl into.
        val loaders = methods.filter { m -> m.instructions().any { it.methodRef()?.definingClass == EXT } }
            .filter { m -> m.instructions().any { it.methodRef()?.name == "onPageLoad" } }
        assertEquals(1, loaders.size, "exactly one host method calls onPageLoad: ${loaders.map { it.name }}")
        val loader = loaders.single()
        assertEquals(
            listOf("Lorg/chromium/content_public/browser/Page;", "L", "Lorg/chromium/url/GURL;", "Z", "I").map { it.take(1) },
            loader.parameterTypes.map { it.toString().take(1) },
        )
        assertEquals("Lorg/chromium/url/GURL;", loader.parameterTypes[2].toString())
        val loaderInsns = loader.instructions()
        val callAt = loaderInsns.indexOfFirst { it.methodRef()?.name == "onPageLoad" }
        val call = loaderInsns[callAt] as FiveRegisterInstruction
        val tabLoad = loaderInsns[callAt - 1]
        assertTrue(
            tabLoad.opcode == Opcode.IGET_OBJECT && tabLoad.fieldRef()?.type == TAB_IMPL &&
                (tabLoad as TwoRegisterInstruction).registerA == call.registerC,
            "onPageLoad is not fed the TabImpl just loaded",
        )
        assertEquals(1, call.registerCount)
        assertTrue(
            loaderInsns.count { it.methodRef()?.name == "onPageLoad" } == 1 &&
                loaderInsns.any { it.opcode == Opcode.INVOKE_INTERFACE },
            "the observer dispatch is gone from the page-loaded method",
        )

        // 3. Menu click: the first instructions compare the id, open the manager and return true.
        val action = methods.single { m ->
            m.returnType == "Z" && m.parameterTypes.map(CharSequence::toString).let {
                it.size == 4 && it[0] == "I" && it[1] == "Z" && it[2] == "Landroid/os/Bundle;"
            } && m.instructions().any { it.hasString("org.chromium.chrome.browser.vpn.timer.TimerDialogFragment") }
        }
        val head = action.instructions().take(5)
        assertEquals(
            listOf(Opcode.CONST, Opcode.IF_NE, Opcode.INVOKE_STATIC, Opcode.CONST_4, Opcode.RETURN),
            head.map { it.opcode },
        )
        assertTrue(head[2].methodRef()?.definingClass == EXT && head[2].methodRef()?.name == "openManager")
        assertEquals(1, (head[3] as NarrowLiteralInstruction).narrowLiteral, "the hook must return true")
        val menuId = (head[0] as NarrowLiteralInstruction).narrowLiteral
        assertEquals(0x0b3a1001, menuId, "the fixed menu id")

        // 4. Menu row: one more row made with the factory, one more list add, and our two resource ids.
        val builder = menuBuilder(methods)
        val stockBuilder = menuBuilder(stock)
        val insns = builder.instructions()
        val factoryCalls = insns.filter { it.opcode == Opcode.INVOKE_STATIC_RANGE }
        val factory = factoryCalls.last().methodRef()!!
        fun List<Instruction>.rowCalls() = count {
            it.opcode == Opcode.INVOKE_STATIC_RANGE && it.methodRef()?.let { r ->
                r.definingClass == factory.definingClass && r.name == factory.name
            } == true
        }
        assertEquals(stockBuilder.instructions().rowCalls() + 1, insns.rowCalls(), "one extra menu row factory call")
        val adder = insns.drop(insns.indexOf(factoryCalls.last())).first { it.opcode == Opcode.INVOKE_VIRTUAL }.methodRef()!!
        fun List<Instruction>.adds() = count {
            it.opcode == Opcode.INVOKE_VIRTUAL && it.methodRef()?.let { r ->
                r.definingClass == adder.definingClass && r.name == adder.name && r.parameterTypes == adder.parameterTypes
            } == true
        }
        assertEquals(stockBuilder.instructions().adds() + 1, insns.adds(), "one extra list add")
        val idConsts = insns.filter { it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == menuId }
        assertEquals(1, idConsts.size, "the menu id is loaded exactly once in the menu builder")
        assertTrue(
            insns.any { it.methodRef()?.definingClass == EXT && it.methodRef()?.name == "titleId" },
            "the row title is not looked up through BraveMonkey.titleId",
        )
        // It sits between the Settings row and the Exit row: our factory call is the second to last.
        val ours = insns.indexOf(factoryCalls[factoryCalls.size - 2])
        assertTrue(
            insns.indexOf(idConsts.single()) in (insns.indexOf(factoryCalls[factoryCalls.size - 3]) + 1) until ours,
            "the Userscripts row is not placed between the Settings and Exit rows",
        )

        // The clone uses the registers of the stock Exit row: new row, factory arguments, result,
        // init and list-add operands, and the iget (register A/B and field) all match.
        fun rowRegs(list: List<Instruction>, call: Instruction): List<Any> {
            val at = list.indexOf(call)
            val start = (0 until at).last { list[it].opcode == Opcode.NEW_INSTANCE }
            val end = (at until list.size).first { list[it].opcode == Opcode.INVOKE_VIRTUAL }
            val block = list.subList(start, end + 1)
            val range = call as RegisterRangeInstruction
            val iget = block.single { it.opcode == Opcode.IGET_OBJECT } as TwoRegisterInstruction
            val init = block.single { it.opcode == Opcode.INVOKE_DIRECT } as FiveRegisterInstruction
            val add = list[end] as FiveRegisterInstruction
            return listOf(
                (block.first() as OneRegisterInstruction).registerA,
                range.startRegister, range.registerCount,
                (block.single { it.opcode == Opcode.MOVE_RESULT_OBJECT } as OneRegisterInstruction).registerA,
                init.registerCount, init.registerC, init.registerD, init.registerE,
                add.registerCount, add.registerC, add.registerD,
                iget.registerA, iget.registerB, ((iget as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" },
            )
        }
        val stockInsns = stockBuilder.instructions()
        val stockExit = stockInsns.last { it.opcode == Opcode.INVOKE_STATIC_RANGE }
        val stockRegs = rowRegs(stockInsns, stockExit)
        assertEquals(stockRegs, rowRegs(insns, factoryCalls[factoryCalls.size - 2]), "the clone differs from the stock Exit row")
        assertEquals(stockRegs, rowRegs(insns, factoryCalls.last()), "the Exit row itself changed")

        // 5. Resources, read from the patched binary manifest (and the stock one as the control).
        val patched = patchedApk ?: error("onPatchedApk was not called")
        fun activities(file: File): Map<String, AxmlElement> = ZipFile(file).use { zip ->
            readAxml(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
                .descendants("activity")
                .associateBy { it.attr("name") ?: "" }
        }
        val prefix = "app.template.extension.bravemonkey."
        val before = activities(apk)
        assertTrue(before.keys.none { it.startsWith(prefix) }, "the stock manifest already declares bravemonkey activities")
        val after = activities(patched)
        val install = after["${prefix}UserscriptInstallActivity"] ?: error("no UserscriptInstallActivity")
        assertEquals(true, install.boolAttr("exported"), "install activity must be exported")
        val datas = install.descendants("intent-filter").flatMap { it.descendants("data") }
        assertTrue(
            datas.any { it.attr("scheme") == "bravemonkey-install" && it.attr("host") == "install" },
            "the install activity's own intent filter lacks scheme bravemonkey-install / host install",
        )
        for (name in listOf("UserscriptManagerActivity", "UserscriptEditorActivity")) {
            val activity = after["$prefix$name"] ?: error("no $name")
            assertEquals(false, activity.boolAttr("exported"), "$name must not be exported")
        }
        assertEquals(before.size + 3, after.size, "exactly three activities were added")
        ZipFile(patched).use { zip ->
            val table = zip.getInputStream(zip.getEntry("resources.arsc")).readBytes()
            for (text in listOf("bravemonkey_userscripts", "Userscripts")) {
                assertTrue(containsUtf16(table, text) || containsUtf8(table, text), "resources.arsc lacks '$text'")
            }
        }
    }
}

/** A start element of a binary AndroidManifest.xml: attributes by local name, children in order. */
private class AxmlElement(val name: String) {
    val attrs = HashMap<String, Pair<Int, Long>>() // local name -> (data type, data)
    val strings = HashMap<String, String>()
    val children = ArrayList<AxmlElement>()
    fun attr(key: String): String? = strings[key]
    fun boolAttr(key: String): Boolean? = attrs[key]?.takeIf { it.first == 0x12 }?.let { it.second != 0L }
    fun descendants(tag: String): List<AxmlElement> =
        children.flatMap { (if (it.name == tag) listOf(it) else emptyList()) + it.descendants(tag) }
}

private fun readAxml(bytes: ByteArray): AxmlElement {
    val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    var pool = emptyList<String>()
    val root = AxmlElement("#root")
    val stack = ArrayList<AxmlElement>().apply { add(root) }
    var pos = b.getShort(2).toInt() and 0xFFFF
    while (pos + 8 <= bytes.size) {
        val type = b.getShort(pos).toInt() and 0xFFFF
        val size = b.getInt(pos + 4)
        when (type) {
            0x0001 -> {
                val count = b.getInt(pos + 8)
                val utf8 = (b.getInt(pos + 16) and 0x100) != 0
                val stringsStart = pos + b.getInt(pos + 20)
                pool = (0 until count).map { i ->
                    var p = stringsStart + b.getInt(pos + 28 + 4 * i)
                    if (utf8) {
                        p += if ((bytes[p].toInt() and 0x80) != 0) 2 else 1
                        var len = bytes[p].toInt() and 0xFF
                        p += 1
                        if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (bytes[p].toInt() and 0xFF); p += 1 }
                        String(bytes, p, len, Charsets.UTF_8)
                    } else {
                        var len = b.getShort(p).toInt() and 0xFFFF
                        p += 2
                        if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF); p += 2 }
                        String(bytes, p, len * 2, Charsets.UTF_16LE)
                    }
                }
            }
            0x0102 -> {
                val element = AxmlElement(pool[b.getInt(pos + 20)])
                val attrStart = pos + 16 + (b.getShort(pos + 24).toInt() and 0xFFFF)
                val attrSize = b.getShort(pos + 26).toInt() and 0xFFFF
                val attrCount = b.getShort(pos + 28).toInt() and 0xFFFF
                for (i in 0 until attrCount) {
                    val at = attrStart + i * attrSize
                    val key = pool[b.getInt(at + 4)]
                    val raw = b.getInt(at + 8)
                    val dataType = bytes[at + 15].toInt() and 0xFF
                    val data = b.getInt(at + 16).toLong() and 0xFFFFFFFFL
                    element.attrs[key] = dataType to data
                    if (raw != -1) element.strings[key] = pool[raw]
                    else if (dataType == 0x03) element.strings[key] = pool[data.toInt()]
                }
                stack.last().children.add(element)
                stack.add(element)
            }
            0x0103 -> stack.removeAt(stack.size - 1)
        }
        if (size <= 0) break
        pos += size
    }
    return root
}
