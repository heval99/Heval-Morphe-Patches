package app.template.patches.shared.misc.theme

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/** `#ff151e22` and `#151e22` are the same color; the decoder writes the short form for an opaque one. */
internal fun String.opaqueColor(): String =
    lowercase().let { if (it.length == 9 && it.startsWith("#ff")) "#" + it.substring(3) else it }

/** One dark theme color: its value in the stock app and the AMOLED value. */
internal data class AmoledColor(val stock: String, val amoled: String)

/**
 * "AMOLED black": an opt-in resource patch that rewrites a handful of dark theme `<color>` values in
 * [colorsPath] (a decoded values file of the app, for example `res/values/colors.xml` or
 * `res/values-night/colors.xml`). Every color is checked against its stock value first and every
 * listed color must exist, so a palette rework fails the patch instead of recoloring something else.
 * It is a build-time change (colors are resources), not a Patch settings switch.
 */
internal fun amoledBlackResourcePatch(
    compatibility: Compatibility,
    colorsPath: String,
    colors: Map<String, AmoledColor>,
) = resourcePatch(
    name = "AMOLED black",
    description = "Makes the dark theme pure black on OLED screens.",
    default = false,
) {
    compatibleWith(compatibility)

    execute {
        decode(colorsPath)
        val remaining = colors.keys.toMutableSet()
        document(colorsPath).use { doc ->
            val nodes = doc.getElementsByTagName("color")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as? Element ?: continue
                val name = node.getAttribute("name")
                val color = colors[name] ?: continue
                val value = node.textContent?.trim()
                if (value == null || value.opaqueColor() != color.stock.opaqueColor()) {
                    throw PatchException("$colorsPath: $name is $value, expected ${color.stock}")
                }
                node.textContent = color.amoled.opaqueColor()
                remaining -= name
            }
        }
        if (remaining.isNotEmpty()) throw PatchException("$colorsPath: missing colors $remaining")
    }
}

/** One theme attribute: the color it references in the stock app and the AMOLED color it gets. */
internal data class AmoledAttribute(val stockRef: String, val amoled: String)

/**
 * The same patch for an app whose dark theme is a style of theme attributes instead of plain
 * color names (FotMob): inside [styleName] of [stylesPath], every listed `<item>` must reference its
 * stock color and is re-pointed at the AMOLED color. The shared color resources are not touched, so
 * the light theme and every other use of the stock gray stay as they were.
 */
internal fun amoledBlackStylePatch(
    compatibility: Compatibility,
    stylesPath: String,
    styleName: String,
    attributes: Map<String, AmoledAttribute>,
) = resourcePatch(
    name = "AMOLED black",
    description = "Makes the dark theme pure black on OLED screens.",
    default = false,
) {
    compatibleWith(compatibility)

    execute {
        decode(stylesPath)
        val remaining = attributes.keys.toMutableSet()
        document(stylesPath).use { doc ->
            val styles = doc.getElementsByTagName("style")
            val style = (0 until styles.length).map { styles.item(it) as Element }.singleOrNull { it.getAttribute("name") == styleName }
                ?: throw PatchException("$stylesPath: style $styleName not found exactly once")
            val items = style.getElementsByTagName("item")
            for (index in 0 until items.length) {
                val item = items.item(index) as? Element ?: continue
                val name = item.getAttribute("name")
                val attribute = attributes[name] ?: continue
                val value = item.textContent?.trim()
                if (value != attribute.stockRef) {
                    throw PatchException("$stylesPath: $styleName $name is $value, expected ${attribute.stockRef}")
                }
                item.textContent = attribute.amoled
                remaining -= name
            }
        }
        if (remaining.isNotEmpty()) throw PatchException("$stylesPath: $styleName is missing items $remaining")
    }
}
