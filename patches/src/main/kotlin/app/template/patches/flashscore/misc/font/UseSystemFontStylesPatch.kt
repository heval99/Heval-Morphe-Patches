package app.template.patches.flashscore.misc.font

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_FLASHSCORE
import org.w3c.dom.Element

private const val STYLES = "res/values/styles.xml"

/**
 * The XML half of "Use system font". Flashscore's text appearances and three layouts name the
 * custom Livesport families directly (`@font/livesport`, `@font/livesport_bold`,
 * `@font/livesport_extrabold`, `@font/flashscore_numbers_regular`, ...), so every TextView that uses
 * them keeps the condensed app font even when the Compose typography is redirected.
 *
 * Each reference is replaced with the matching system family, keeping the weight: extrabold ->
 * `sans-serif-black`, bold/medium -> `sans-serif-medium`, regular -> `sans-serif`. The font files
 * themselves are left in the APK; nothing else in the resource table is touched.
 */
@Suppress("unused")
val useSystemFontStylesPatch = resourcePatch(
    name = "Use system font (styles)",
    description = "Points the app's XML text styles and layouts at the system font.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_FLASHSCORE)

    execute {
        // Styles only. Decoding res/layout here would reset the layout tree the "Patch settings"
        // row patch edits (the patcher re-reads the requested layout entries, activity_settings
        // included), so the three layouts that also set fontFamily inline are left to that patch's
        // pass instead of being rewritten here.
        decode(STYLES)
        var replaced = 0

        // <item name="android:fontFamily">@font/...</item> in the text appearances.
        document(STYLES).use { doc ->
            val items = doc.getElementsByTagName("item")
            for (index in 0 until items.length) {
                val item = items.item(index) as? Element ?: continue
                val name = item.getAttribute("name")
                if (name != "android:fontFamily" && name != "fontFamily") continue
                val value = item.textContent?.trim().orEmpty()
                if (!value.startsWith("@font/")) continue
                item.textContent = systemFamily(value)
                replaced++
            }
        }

        if (replaced == 0) {
            throw PatchException("$STYLES: no @font/ reference was rewritten")
        }
    }
}

/** Maps a `@font/...` reference to the system family with the closest weight. */
private fun systemFamily(resource: String): String {
    val name = resource.substringAfterLast('/').lowercase()
    return when {
        "extrabold" in name || "extra_bold" in name -> "sans-serif-black"
        "bold" in name || "medium" in name || "semi" in name -> "sans-serif-medium"
        else -> "sans-serif"
    }
}

