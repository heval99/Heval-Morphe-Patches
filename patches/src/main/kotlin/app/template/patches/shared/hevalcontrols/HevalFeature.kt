package app.template.patches.shared.hevalcontrols

/**
 * One feature shown on the Patch settings screen, registered by the patch that implements it.
 *
 * @param id Stable key, `^[a-z][a-z0-9_.]{0,63}$`, conventionally `<app>.<feature>` (for example
 *        `linkedin.hide_promoted`). It names the saved choice, so never rename a shipped id.
 * @param name Row title.
 * @param description Row summary: what changes when the feature is on.
 * @param category Section the row is listed under, in first-registration order.
 * @param default Whether the feature is on before the user chooses (and before the settings
 *        engine has started). Keep it equal to today's always-on behaviour for converted patches.
 * @param restartRequired The app reads the gate once per process (for example in
 *        `Application.onCreate`), so a change applies after a restart. The screen shows a badge
 *        and a "Restart now" bar.
 * @param buildTimeOnly Applied at patch time (manifest, resources, removed code). Listed
 *        read-only as "Applied at patch time"; it has no runtime gate.
 * @param dependencies Ids of features that must be on for this one to take effect. A dependency
 *        missing from the APK makes this feature "Unsupported" (stock) at runtime.
 */
data class HevalFeature(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val default: Boolean = true,
    val restartRequired: Boolean = false,
    val buildTimeOnly: Boolean = false,
    val dependencies: List<String> = emptyList(),
) {
    init {
        require(ID_REGEX.matches(id)) { "Invalid Patch settings feature id: $id" }
        require(name.isNotBlank()) { "Feature $id has no name" }
        require(category.isNotBlank()) { "Feature $id has no category" }
        dependencies.forEach { dependency ->
            require(ID_REGEX.matches(dependency)) { "Feature $id has an invalid dependency id: $dependency" }
            require(dependency != id) { "Feature $id depends on itself" }
        }
        require(dependencies.toSet().size == dependencies.size) { "Feature $id lists a dependency twice" }
    }

    companion object {
        val ID_REGEX = Regex("^[a-z][a-z0-9_.]{0,63}$")
    }
}

/**
 * Writes the registry string that the patch puts into `Registry.encoded()` in the extension.
 * The Java decoder is `RegistryCodec` in `extensions/hevalcontrols/core`; both sides are pinned by
 * `extensions/hevalcontrols/core/src/test/resources/registry-v1.txt`.
 *
 * Version 1: a header line `hevalcontrols-registry:1`, then one line per feature with eight
 * tab-separated fields: id, name, description, category, default, restartRequired,
 * buildTimeOnly (`0`/`1`) and comma-separated dependencies. Text fields escape `\`, tab, LF and
 * CR as `\\`, `\t`, `\n` and `\r`. Bump [VERSION] only for an incompatible change: the decoder
 * ignores extra trailing fields, so adding a field at the end keeps version 1 readable.
 */
object HevalRegistryCodec {
    const val HEADER_PREFIX = "hevalcontrols-registry:"
    const val VERSION = 1

    fun encode(features: List<HevalFeature>): String {
        val duplicates = features.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate Patch settings feature ids: $duplicates" }

        return buildString {
            append(HEADER_PREFIX).append(VERSION).append('\n')
            features.forEach { feature ->
                listOf(
                    feature.id,
                    escape(feature.name),
                    escape(feature.description),
                    escape(feature.category),
                    flag(feature.default),
                    flag(feature.restartRequired),
                    flag(feature.buildTimeOnly),
                    feature.dependencies.joinToString(","),
                ).joinTo(this, "\t")
                append('\n')
            }
        }
    }

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private fun escape(text: String) = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }
}
