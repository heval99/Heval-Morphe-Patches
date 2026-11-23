package app.template.patches.brave.userscripts

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.addInstructionsWithLabelsChecked
import app.template.patches.shared.requireNoBranchTargetAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.ReferenceUtil
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/template/extension/bravemonkey/BraveMonkey;"
private const val EXTENSION_PACKAGE = "app.template.extension.bravemonkey"
// The patcher cannot resolve ids of resources a patch adds, so the row id is a fixed value outside the app's
// 0x7f resource range (Brave only compares it and turns its entry name into a pref key, which falls back
// to "shown" on NotFoundException) and the title is looked up by name at runtime (BraveMonkey.titleId).
private const val MENU_ID = 0x0b3a1001
private const val MENU_TITLE_NAME = "bravemonkey_userscripts"
private const val MANIFEST = "AndroidManifest.xml"
private const val STRINGS = "res/values/strings.xml"
private const val NO_ACTION_BAR = "@android:style/Theme.Material.NoActionBar"

private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference

/**
 * Declares the extension's three screens in the manifest (the install screen answers
 * `bravemonkey-install://install` links) and adds the menu row's title. Names are fully
 * qualified, so the app's own package does not matter.
 */
private val userscriptsResourcePatch = resourcePatch {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    execute {
        document(MANIFEST).use { doc ->
            val applications = doc.getElementsByTagName("application")
            if (applications.length == 0) throw PatchException("$MANIFEST has no <application>")
            val application = applications.item(0) as Element

            fun activity(name: String, exported: Boolean): Element =
                doc.createElement("activity").also {
                    it.setAttribute("android:name", "$EXTENSION_PACKAGE.$name")
                    it.setAttribute("android:exported", exported.toString())
                    it.setAttribute("android:theme", NO_ACTION_BAR)
                    it.setAttribute("android:excludeFromRecents", "true")
                    it.setAttribute("android:windowSoftInputMode", "adjustResize")
                    application.appendChild(it)
                }

            val install = activity("UserscriptInstallActivity", exported = true)
            val filter = doc.createElement("intent-filter").also { install.appendChild(it) }
            filter.appendChild(doc.createElement("action").also {
                it.setAttribute("android:name", "android.intent.action.VIEW")
            })
            filter.appendChild(doc.createElement("category").also {
                it.setAttribute("android:name", "android.intent.category.DEFAULT")
            })
            filter.appendChild(doc.createElement("category").also {
                it.setAttribute("android:name", "android.intent.category.BROWSABLE")
            })
            filter.appendChild(doc.createElement("data").also {
                it.setAttribute("android:scheme", "bravemonkey-install")
                it.setAttribute("android:host", "install")
            })
            activity("UserscriptManagerActivity", exported = false)
            activity("UserscriptEditorActivity", exported = false)
        }

        decode(STRINGS)
        document(STRINGS).use { strings ->
            val resources = strings.getElementsByTagName("resources").item(0) as Element
            resources.appendChild(strings.createElement("string").also {
                it.setAttribute("name", MENU_TITLE_NAME)
                it.textContent = "Userscripts"
            })
        }
    }
}

@Suppress("unused")
val userscriptsPatch = bytecodePatch(
    name = "Userscripts",
    description = "Adds a userscript manager to the Brave menu that runs scripts on the pages you visit.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    dependsOn(userscriptsResourcePatch)
    extendWith("extensions/bravemonkey.mpe")

    execute {
        // ── 1. App start: install the extension before any activity or tab exists ──────────
        ApplicationOnCreateFingerprint.method.addInstructionsChecked(
            0,
            "invoke-static {p0}, $EXTENSION->install(Landroid/app/Application;)V",
        )

        // ── 2. Page loaded: hand the finished tab to the extension ─────────────────────────
        //  Chromium's primary-main-frame load callback. The method has no free register, but the
        //  tab observer dispatch it makes already holds the TabImpl in the register it passes as
        //  the Tab argument, so that register is read, not overwritten. The call goes in right
        //  after the TabImpl is loaded and before the observers run; the extension only reads the
        //  tab and filters on http/https and incognito itself.
        val load = PageLoadFinishedFingerprint.method
        val loadInsns = load.instructions.toList()
        val dispatch = loadInsns.indexOfFirst {
            val ref = it.ref()
            it.opcode == Opcode.INVOKE_INTERFACE && ref is MethodReference && ref.returnType == "V" &&
                ref.parameterTypes.map(CharSequence::toString) == listOf(TAB_TYPE, GURL_TYPE)
        }
        if (dispatch < 0) throw PatchException("${load.name}: the TabObserver dispatch is gone")
        val tabRegister = (loadInsns[dispatch] as FiveRegisterInstruction).registerD
        val tabLoad = (0 until dispatch).lastOrNull {
            val ref = loadInsns[it].ref()
            loadInsns[it].opcode == Opcode.IGET_OBJECT && ref is FieldReference && ref.type == TAB_IMPL_TYPE &&
                (loadInsns[it] as TwoRegisterInstruction).registerA == tabRegister
        } ?: throw PatchException("${load.name}: nothing loads the TabImpl into v$tabRegister before the dispatch")
        if (tabRegister >= 16) throw PatchException("${load.name}: the tab register v$tabRegister does not fit")
        load.requireNoBranchTargetAt(tabLoad + 1, "page-loaded hook")
        load.addInstructionsChecked(
            tabLoad + 1,
            "invoke-static {v$tabRegister}, $EXTENSION->onPageLoad(Ljava/lang/Object;)V",
        )

        // ── 3. Menu row: "Userscripts" before Exit in the page menu ─────────────────────────
        //  The row is a clone of the Exit row's instructions (same registers, field and method
        //  references) with other ids, so R8 renumbering cannot make it invalid. Everything the
        //  clone writes is rewritten by the Exit row right after, and the two registers it only
        //  reads (the disabled flag and the list index zero) are untouched at this point.
        val menuId = MENU_ID
        val factory = MenuItemFactoryFingerprint.method
        val menu = PageMenuBuilderFingerprint.method
        val menuInsns = menu.instructions.toList()

        val loadIndex = menuInsns.indexOfLast {
            val ref = it.ref()
            it.opcode == Opcode.INVOKE_STATIC_RANGE && ref is MethodReference &&
                ref.definingClass == factory.definingClass && ref.name == factory.name
        }
        if (loadIndex < 0) throw PatchException("${menu.name}: no menu row is made with ${factory.name}")
        val start = (0 until loadIndex).last { menuInsns[it].opcode == Opcode.NEW_INSTANCE }
        val end = (loadIndex until menuInsns.size).first { menuInsns[it].opcode == Opcode.INVOKE_VIRTUAL }
        if (menuInsns[start - 1].opcode != Opcode.INVOKE_VIRTUAL) {
            throw PatchException("${menu.name}: the row before Exit is not added to the list")
        }

        var created: Pair<Int, TypeReference>? = null
        var iget: TwoRegisterInstruction? = null
        var moveResult = -1
        var init: FiveRegisterInstruction? = null
        var add: FiveRegisterInstruction? = null
        val consts = mutableSetOf<Int>()
        for (index in start..end) {
            val insn = menuInsns[index]
            when (insn.opcode) {
                Opcode.NEW_INSTANCE ->
                    created = (insn as OneRegisterInstruction).registerA to (insn.ref() as TypeReference)
                Opcode.CONST -> consts += (insn as OneRegisterInstruction).registerA
                Opcode.IGET_OBJECT -> iget = insn as TwoRegisterInstruction
                Opcode.INVOKE_STATIC_RANGE -> Unit
                Opcode.MOVE_RESULT_OBJECT -> moveResult = (insn as OneRegisterInstruction).registerA
                Opcode.INVOKE_DIRECT -> init = insn as FiveRegisterInstruction
                Opcode.INVOKE_VIRTUAL -> add = insn as FiveRegisterInstruction
                else -> throw PatchException("${menu.name}: unexpected ${insn.opcode} in the Exit row")
            }
        }
        val call = menuInsns[loadIndex] as RegisterRangeInstruction
        val first = call.startRegister
        if (call.registerCount != 6 || created == null || iget == null || moveResult < 0 || init == null ||
            add == null || consts != setOf(first + 2, first + 3, first + 4) ||
            (iget as TwoRegisterInstruction).registerA != first + 1
        ) {
            throw PatchException("${menu.name}: the Exit row no longer has the expected shape")
        }
        val newReg = created.first
        val igetRef = (iget as Instruction).ref()
        val row = """
            new-instance v$newReg, ${ReferenceUtil.getReferenceString(created.second)}
            const v${first + 2}, 0x${menuId.toString(16)}
            invoke-static {v$first}, $EXTENSION->titleId(Landroid/content/Context;)I
            move-result v${first + 3}
            const/16 v${first + 4}, 0x0
            iget-object v${first + 1}, v${iget.registerB}, ${ReferenceUtil.getReferenceString(igetRef!!)}
            invoke-static/range {v$first .. v${first + 5}}, ${ReferenceUtil.getReferenceString(menuInsns[loadIndex].ref()!!)}
            move-result-object v$moveResult
            invoke-direct {v$newReg, v$moveResult, v${init.registerE}}, ${ReferenceUtil.getReferenceString(init.ref()!!)}
            invoke-virtual {v${add.registerC}, v$newReg}, ${ReferenceUtil.getReferenceString(add.ref()!!)}
        """.trimIndent()
        menu.requireNoBranchTargetAt(start, "menu row")
        menu.addInstructionsChecked(start, row)

        // ── 4. Menu click: open the manager when the row's id is chosen ────────────────────
        //  BraveActivity.onMenuOrKeyboardAction(id, ...): v0 is a free local at the head (the first
        //  original instruction writes it).
        MenuActionFingerprint.method.addInstructionsWithLabelsChecked(
            0,
            """
                const v0, 0x${menuId.toString(16)}
                if-ne p1, v0, :skip
                invoke-static {p0}, $EXTENSION->openManager(Landroid/app/Activity;)V
                const/4 v0, 0x1
                return v0
                :skip
                nop
            """.trimIndent(),
        )
    }
}
