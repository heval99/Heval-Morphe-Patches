package app.template.patches.brave.userscripts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal const val TAB_TYPE = "Lorg/chromium/chrome/browser/tab/Tab;"
internal const val TAB_IMPL_TYPE = "Lorg/chromium/chrome/browser/tab/TabImpl;"
internal const val GURL_TYPE = "Lorg/chromium/url/GURL;"
internal const val PROPERTY_MODEL_TYPE = "Lorg/chromium/ui/modelutil/PropertyModel;"

// Brave's real Application class (the manifest names the SplitChromeApplication wrapper); its
// onCreate runs before any activity or tab exists.
internal object ApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lorg/chromium/chrome/browser/base/SplitChromeApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

// Chromium's WebContentsObserver.didFinishLoadInPrimaryMainFrame(Page, RenderFrameHost id, GURL,
// boolean, int) as overridden by the TabWebContentsObserver (R8 names: r1k.a). Same-document
// navigations never reach it. Four other methods share the bare shape, so it is told apart by the
// TabObserver dispatch it makes (an interface call taking (Tab, GURL)V) and the TabImpl field it
// reads to get the tab.
internal object PageLoadFinishedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lorg/chromium/content_public/browser/Page;", "L", GURL_TYPE, "Z", "I"),
    custom = { method, _ ->
        val refs = method.implementation?.instructions?.toList().orEmpty()
            .mapNotNull { (it as? ReferenceInstruction)?.let { insn -> insn.opcode to insn.reference } }
        refs.any { (opcode, ref) ->
            opcode == Opcode.INVOKE_INTERFACE && ref is MethodReference && ref.returnType == "V" &&
                ref.parameterTypes.map(CharSequence::toString) == listOf(TAB_TYPE, GURL_TYPE)
        } && refs.any { (opcode, ref) ->
            opcode == Opcode.IGET_OBJECT && ref is FieldReference && ref.type == TAB_IMPL_TYPE
        }
    },
)

// The page (three dots) menu builder, hq2.f in 1.100.58: the only no-argument method that returns
// an object and reads both the "add_to_playlist_button" pref and the per-item
// "customizable_brave_menu_item_id_" prefix.
internal object PageMenuBuilderFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        string("add_to_playlist_button"),
        string("customizable_brave_menu_item_id_"),
    ),
)

// The static factory every menu row is made with: (Context, <menu model>, itemId, titleRes,
// iconRes, enabled) -> PropertyModel (ib0.l). It skips the icon when iconRes is 0.
internal object MenuItemFactoryFingerprint : Fingerprint(
    returnType = PROPERTY_MODEL_TYPE,
    parameters = listOf("Landroid/content/Context;", "L", "I", "I", "I", "Z"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

// BraveActivity.onMenuOrKeyboardAction(int id, boolean fromMenu, Bundle, <callback>) (t62.z4); the
// VPN timer dialog class name it references is a Chromium-kept string.
internal object MenuActionFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("I", "Z", "Landroid/os/Bundle;", "L"),
    filters = listOf(string("org.chromium.chrome.browser.vpn.timer.TimerDialogFragment")),
)
