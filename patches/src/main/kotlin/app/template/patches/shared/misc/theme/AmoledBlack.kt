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
