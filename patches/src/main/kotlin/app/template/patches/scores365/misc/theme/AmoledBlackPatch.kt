package app.template.patches.scores365.misc.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_365SCORES
import org.w3c.dom.Element

private const val COLORS = "res/values/colors.xml"

/** `#ff151e22` and `#151e22` are the same color; the decoder writes the short form for an opaque one. */
private fun String.opaque(): String = lowercase().let { if (it.length == 9 && it.startsWith("#ff")) "#" + it.substring(3) else it }

/**
 * The dark theme colors that are not black yet (15.0.0): name to (stock value, AMOLED value). The
 * dark theme background is already `#000000` (`dark_theme_background`); the cards, the scores list
 * rows and the secondary surfaces are `#151e22` and the toolbar `#151e22`, the picks trend card
 * `#21292d`. The AMOLED values keep cards a hair above black (`#0a0a0a`, `#121212` for the trend
 * card) so the dividers and card edges still read on an OLED panel, and the toolbar is pure black so
 * it merges with the status bar. Dividers, texts and accents are left alone.
 */
private val AMOLED_COLORS = mapOf(
    "dark_theme_card_background" to ("#ff151e22" to "#ff0a0a0a"),
    "dark_theme_scores_new" to ("#ff151e22" to "#ff0a0a0a"),
    "dark_theme_surface_secondary" to ("#ff151e22" to "#ff0a0a0a"),
    "dark_theme_toolbar_color" to ("#ff151e22" to "#ff000000"),
    "dark_theme_picks_trend_card" to ("#ff21292d" to "#ff121212"),
)

// AMOLED black (2026-11-09, owner request for the sports apps). Opt-in. The app has its own theme
// switch ("Themes" in its Settings) and keeps the dark palette in plain `dark_theme_*` colors, so
// the whole change is five color values; a build-time edit, not a Patch settings switch (colors
// are resources). Every value is checked against the stock value first, so a palette rework fails
// the patch instead of recoloring something else. Not covered: screens that draw hard-coded or
// image-based grays, the light theme, and a user who has not chosen the dark theme.
@Suppress("unused")
val amoledBlackPatch = resourcePatch(
    name = "AMOLED black",
    description = "Makes the dark theme pure black on OLED screens.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_365SCORES)

    execute {
        decode(COLORS)
        val remaining = AMOLED_COLORS.keys.toMutableSet()
        document(COLORS).use { doc ->
            val colors = doc.getElementsByTagName("color")
            for (index in 0 until colors.length) {
                val color = colors.item(index) as? Element ?: continue
                val name = color.getAttribute("name")
                val (stock, amoled) = AMOLED_COLORS[name] ?: continue
                val value = color.textContent?.trim()
                if (value == null || value.opaque() != stock.opaque()) {
                    throw PatchException("$COLORS: $name is $value, expected $stock")
                }
                color.textContent = amoled.opaque()
                remaining -= name
            }
        }
        if (remaining.isNotEmpty()) throw PatchException("$COLORS: missing colors $remaining")
    }
}
