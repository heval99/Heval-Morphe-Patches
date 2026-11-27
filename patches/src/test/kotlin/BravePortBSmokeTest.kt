import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val EXT_PREFIX = "Lapp/template/extension/brave/"
private const val HOOKS = "Lapp/template/extension/brave/ShareHooks;"
private const val SCRIPTS = "Lapp/template/extension/brave/PageScripts;"
private const val DISPLAY = "Lapp/template/extension/brave/DisplayHelper;"
private const val AMOLED = "Lapp/template/extension/brave/AmoledColors;"

class BravePortBSmokeTest {

    @TempDir
    lateinit var workDir: File

    private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
    private fun Instruction.hasString(value: String) =
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    private fun Instruction.callsHook(owner: String, name: String) =
        methodRef()?.let { it.definingClass == owner && it.name == name } == true
    private fun Method.refs() = instructions().mapNotNull { it.methodRef() }

    @Test
    fun `batch B patches rewrite their anchors and coexist with Userscripts`() {
        val root = repoRoot()
        val apk = File(root, "apks/brave/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val classes: List<ClassDef> = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = "com.brave.browser",
            version = "1.100.59",
            patchNames = setOf(
                "Strip tracking parameters",
                "Force copy and paste",
                "Force picture-in-picture",
                "Force max refresh rate",
                "Strip menu and settings bloat",
                "AMOLED black theme",
                "Userscripts",
            ),
            allPatches = loadAllPatches(newestPatchBundle(root)),
        )
        val host = classes.filterNot { it.type.startsWith(EXT_PREFIX) || it.type.startsWith("Lapp/template/extension/") }
            .flatMap { it.methods }.filter { it.implementation != null }

        // 1. Clipboard sink: the (ClipData)V that calls setPrimaryClip starts with the sanitizer.
        val sink = host.single { m ->
            m.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/ClipData;") && m.returnType == "V" &&
                m.refs().any { it.name == "setPrimaryClip" }
        }
        val sinkInsns = sink.instructions()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, sinkInsns[0].opcode)
        assertTrue(sinkInsns[0].callsHook(HOOKS, "sanitizeClip"), "clipboard sink must call sanitizeClip first")
        // p1 (the ClipData; p0 is this) goes in and the result goes back into it.
        val clipRegister = sink.implementation!!.registerCount - 1
        assertEquals(clipRegister, (sinkInsns[0] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (sinkInsns[0] as RegisterRangeInstruction).registerCount)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, sinkInsns[1].opcode)
        assertEquals(clipRegister, (sinkInsns[1] as OneRegisterInstruction).registerA)

        // 2. Share Intent builder: every return-object is preceded by sanitizeIntent.
        val builder = host.single { m ->
            m.returnType == "Landroid/content/Intent;" && m.instructions().any { it.hasString("org.chromium.chrome.browser.share_origin") }
        }
        val builderInsns = builder.instructions()
        val builderReturns = builderInsns.indices.filter { builderInsns[it].opcode == Opcode.RETURN_OBJECT }
        assertTrue(builderReturns.isNotEmpty())
        builderReturns.forEach {
            assertTrue(builderInsns[it - 1].callsHook(HOOKS, "sanitizeIntent"), "share builder return at $it is not sanitized")
        }

        // 3. Both plain text share helpers sanitize their text argument first.
        val senders = host.filter { m ->
            m.returnType == "V" && m.parameterTypes.size == 2 && m.parameterTypes[0].startsWith("L") &&
                m.parameterTypes[1] == "Ljava/lang/String;" &&
                m.instructions().any { it.hasString("android.intent.action.SEND") } &&
                m.instructions().any { it.hasString("text/plain") }
        }
        assertEquals(2, senders.size, "both ACTION_SEND text helpers")
        senders.forEach { assertTrue(it.instructions()[0].callsHook(HOOKS, "sanitizeText")) }

        // 4. Page scripts: the page-load hook and both enables at app start (next to Userscripts' install).
        val pageLoad = host.filter { m -> m.refs().any { it.definingClass == SCRIPTS && it.name == "onPageLoad" } }
        assertEquals(1, pageLoad.size, "exactly one onPageLoad hook, shared by both script patches")
        val appStart = host.single { m -> m.refs().any { it.definingClass == SCRIPTS && it.name == "enableCopyPaste" } }
        assertTrue(appStart.refs().any { it.name == "enablePictureInPicture" })
        assertTrue(appStart.refs().any { it.definingClass.startsWith("Lapp/template/extension/bravemonkey/") && it.name == "install" })
        assertTrue(host.any { m -> m.refs().any { it.definingClass.startsWith("Lapp/template/extension/bravemonkey/") && it.name == "onPageLoad" } })

        // 5. Refresh rate: WindowAndroid(F)V reads its modes list and asks the helper first.
        val refresh = host.single { m ->
            m.definingClass == "Lorg/chromium/ui/base/WindowAndroid;" && m.parameterTypes.map(CharSequence::toString) == listOf("F") &&
                m.instructions().any { (it as? ReferenceInstruction)?.reference.let { r -> r is StringReference && r.string.startsWith("Refresh rate not supported") } }
        }
        // The hook reads the modes list field and calls the Window getter by what the method itself uses, then
        // asks the helper and returns when it applied a mode. No fixed field or getter name is assumed.
        val refreshInsns = refresh.instructions()
        val modesField = (refreshInsns[0] as ReferenceInstruction).reference as FieldReference
        assertEquals(Opcode.IGET_OBJECT, refreshInsns[0].opcode)
        assertEquals("Ljava/util/ArrayList;", modesField.type)
        val windowCall = refreshInsns[1].methodRef()!!
        assertEquals("Landroid/view/Window;", windowCall.returnType)
        assertEquals("Lorg/chromium/ui/base/WindowAndroid;", windowCall.definingClass)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, refreshInsns[2].opcode)
        assertTrue(refreshInsns[3].callsHook(DISPLAY, "applyMaxRefreshRate"))
        assertEquals(Opcode.MOVE_RESULT, refreshInsns[4].opcode)
        assertEquals(Opcode.IF_EQZ, refreshInsns[5].opcode)
        assertEquals(Opcode.RETURN_VOID, refreshInsns[6].opcode)
        // 6. Page menu: before the return the eight bloat ids are put in an int[] and a row remover is called.
        val menu = host.single { m ->
            m.returnType.startsWith("L") && m.parameterTypes.isEmpty() &&
                m.instructions().any { it.hasString("add_to_playlist_button") } &&
                m.instructions().any { it.hasString("customizable_brave_menu_item_id_") }
        }
        val menuInsns = menu.instructions()
        val menuReturns = menuInsns.indices.filter { menuInsns[it].opcode == Opcode.RETURN_OBJECT }
        assertTrue(menuReturns.isNotEmpty())
        menuReturns.forEach { menuReturn ->
            val menuCall = menuInsns[menuReturn - 1].methodRef()
            assertTrue(menuCall != null && menuCall.parameterTypes.map(CharSequence::toString).last() == "[I", "row remover call before return $menuReturn")
            // the call gets the returned model and the int[] that was just filled
            val call = menuInsns[menuReturn - 1] as FiveRegisterInstruction
            assertEquals((menuInsns[menuReturn] as OneRegisterInstruction).registerA, call.registerC, "model register")
            assertEquals(8, menuInsns.subList(menuReturn - 27, menuReturn).count { it.opcode == Opcode.APUT }, "eight ids")
        }

        // 7. Settings: each bloat key is removed before every return of the update method.
        val update = host.single { m ->
            m.definingClass == "Lorg/chromium/chrome/browser/settings/BraveMainPreferencesBase;" && m.parameterTypes.isEmpty() &&
                m.instructions().any { it.hasString("features_section") } && m.instructions().any { it.hasString("brave_vpn_callout") }
        }
        val updateInsns = update.instructions()
        val keys = listOf("brave_news_v2", "brave_wallet", "brave_vpn", "brave_vpn_callout", "brave_leo")
        val returns = updateInsns.indices.filter { updateInsns[it].opcode == Opcode.RETURN_VOID }
        assertTrue(returns.isNotEmpty())
        returns.forEach { r ->
            val tail = updateInsns.subList(r - 11, r)
            assertEquals(Opcode.MOVE_OBJECT_FROM16, tail[0].opcode)
            keys.forEachIndexed { i, key ->
                assertTrue(tail[1 + 2 * i].hasString(key), "return at $r removes $key")
                assertEquals(Opcode.INVOKE_VIRTUAL, tail[2 + 2 * i].opcode)
            }
        }

        // 8. AMOLED: the color resolver remaps its result.
        val resolver = host.single { m ->
            m.returnType == "I" && m.parameterTypes.size == 2 && m.parameterTypes[0].startsWith("L") &&
                m.parameterTypes[1] == "Landroid/util/TypedValue;" &&
                m.refs().any { it.name == "getColor" && it.definingClass == "Landroid/content/Context;" }
        }
        val resolverInsns = resolver.instructions()
        // Entry: the Context parameter (the first parameter register) goes to enter(Context) before anything can reuse it.
        val params = resolver.implementation!!.registerCount - 2
        val enter = resolverInsns[0]
        assertTrue(enter.callsHook(AMOLED, "enter"), "enter(Context) first")
        assertEquals(params, (enter as FiveRegisterInstruction).registerC, "enter gets the Context parameter register")
        // Return: remap(int) gets the very register the method returns and writes the result back to it, so the
        // Context (whose register the stock code reuses for the int) is never passed as an int.
        val resolverReturns = resolverInsns.indices.filter { resolverInsns[it].opcode == Opcode.RETURN }
        assertTrue(resolverReturns.isNotEmpty())
        resolverReturns.forEach { resolverReturn ->
            val returned = (resolverInsns[resolverReturn] as OneRegisterInstruction).registerA
            val remap = resolverInsns[resolverReturn - 2]
            assertTrue(remap.callsHook(AMOLED, "remap"), "return $resolverReturn is remapped")
            assertEquals(listOf("I"), remap.methodRef()!!.parameterTypes.map(CharSequence::toString))
            assertEquals(returned, (remap as FiveRegisterInstruction).registerC, "remap gets the returned register")
            assertEquals(Opcode.MOVE_RESULT, resolverInsns[resolverReturn - 1].opcode)
            assertEquals(returned, (resolverInsns[resolverReturn - 1] as OneRegisterInstruction).registerA)
        }    }
}
