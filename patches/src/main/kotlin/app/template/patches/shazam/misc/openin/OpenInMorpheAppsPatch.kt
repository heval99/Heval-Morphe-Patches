package app.template.patches.shazam.misc.openin

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_SHAZAM
import app.template.patches.shared.addInstructionsChecked
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Element

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/template/extension/shazam/OpenInMorpheAppsPatch;"

private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

private val youTubeMusicPackageOption = stringOption(
    key = "youTubeMusicPackage",
    default = "app.morphe.android.apps.youtube.music",
    values = mapOf("Morphe YouTube Music" to "app.morphe.android.apps.youtube.music"),
    title = "YouTube Music package",
    description = "Package name of the patched YouTube Music app that \"Open in YouTube Music\" should open.",
    required = true,
) { true } // Checked (after trimming) in execute, which can say what is wrong.

private val youTubePackageOption = stringOption(
    key = "youTubePackage",
    default = "app.morphe.android.youtube",
    values = mapOf("Morphe YouTube" to "app.morphe.android.youtube"),
    title = "YouTube package",
    description = "Package name of the patched YouTube app that \"Open in YouTube\" should open.",
    required = true,
) { true } // Checked (after trimming) in execute, which can say what is wrong.

private val spotifyPackageOption = stringOption(
    key = "spotifyPackage",
    default = null,
    title = "Spotify package",
    description = "Package name of a modified Spotify app that \"Open in Spotify\" should open. Leave empty for the stock Spotify.",
    required = false,
) { true }

private const val STOCK_SPOTIFY_PACKAGE = "com.spotify.music"

/** The option value trimmed, [default] when empty, or a PatchException that names the option. */
private fun resolvePackage(title: String, value: String?, default: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return default
    if (!PACKAGE_NAME_REGEX.matches(trimmed)) {
        throw PatchException(
            "\"$title\" is not a valid package name: \"$trimmed\". Use letters, digits and underscores in " +
                "segments that start with a letter, separated by dots, for example com.example.myspoti."
        )
    }
    return trimmed
}

private const val DEFAULT_YOUTUBE_MUSIC_PACKAGE = "app.morphe.android.apps.youtube.music"
private const val DEFAULT_YOUTUBE_PACKAGE = "app.morphe.android.youtube"

/** The three packages the patch uses; the manifest patch and the bytecode patch must both read them from here. */
private class ResolvedPackages(val youTubeMusic: String, val youTube: String, val spotify: String)

private fun resolvePackages() = ResolvedPackages(
    youTubeMusic = resolvePackage("YouTube Music package", youTubeMusicPackageOption.value, DEFAULT_YOUTUBE_MUSIC_PACKAGE)!!,
    youTube = resolvePackage("YouTube package", youTubePackageOption.value, DEFAULT_YOUTUBE_PACKAGE)!!,
    // Empty means the stock Spotify: the extension then leaves the Spotify provider alone.
    spotify = resolvePackage("Spotify package", spotifyPackageOption.value, STOCK_SPOTIFY_PACKAGE)!!,
)

// Shazam targets SDK 36 and its <queries> only lists the stock Google packages, so without these
// entries getApplicationInfo() cannot see the Morphe apps and the remap never applies.
private val openInMorpheAppsManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val manifest = doc.documentElement
            val queries = doc.getElementsByTagName("queries").item(0) as? Element
                ?: doc.createElement("queries").also { manifest.appendChild(it) }

            val existing = buildSet {
                val packages = queries.getElementsByTagName("package")
                for (i in 0 until packages.length) {
                    val name = (packages.item(i) as? Element)?.getAttribute("android:name")
                    if (!name.isNullOrEmpty()) add(name)
                }
            }

            val packages = resolvePackages()
            listOf(packages.youTubeMusic, packages.youTube, packages.spotify)
                .filter { it !in existing }
                .distinct()
                .forEach { packageName ->
                    queries.appendChild(
                        doc.createElement("package").apply { setAttribute("android:name", packageName) },
                    )
                }
        }
    }
}

@Suppress("unused")
val openInMorpheAppsPatch = bytecodePatch(
    name = "Open in Morphe YouTube apps",
    description = "Opens songs in the Morphe YouTube apps, or a modified Spotify, when installed.",
) {
    compatibleWith(COMPATIBILITY_SHAZAM)

    dependsOn(openInMorpheAppsManifestPatch)

    extendWith("extensions/shazam.mpe")

    youTubeMusicPackageOption()
    youTubePackageOption()
    spotifyPackageOption()

    execute {
        val packages = resolvePackages()

        // Hand the option values to the extension.
        mutableClassDefBy(EXTENSION_CLASS_DESCRIPTOR).methods.apply {
            single { it.name == "getYouTubeMusicPackage" }.returnEarly(packages.youTubeMusic)
            single { it.name == "getYouTubePackage" }.returnEarly(packages.youTube)
            single { it.name == "getSpotifyPackage" }.returnEarly(packages.spotify)
        }

        // Cross-check: the config parser must still build providers through this class,
        // otherwise hooking its constructor would silently change nothing.
        val providerType = StreamingProviderToStringFingerprint.classDef.type
        val parser = StreamingProvidersFromConfigFingerprint.method
        val buildsProviders = parser.implementation!!.instructions.any { instruction ->
            instruction.opcode == Opcode.NEW_INSTANCE &&
                ((instruction as ReferenceInstruction).reference as? TypeReference)?.type == providerType
        }
        if (!buildsProviders) {
            throw PatchException("$providerType is no longer built by the streaming provider config parser")
        }

        // Spotify is one of the same config providers (id "spotify", syncable), so this hook also covers
        // "Open in Spotify". The Spotify auth SDK is untouched and keeps talking to the real Spotify.
        // Remap packageName, songUri, artistUri and albumUri (p3..p6) before they are stored.
        StreamingProviderConstructorFingerprint.method.addInstructionsChecked(
            0,
            (3..6).joinToString("\n") { register ->
                """
                    invoke-static/range { p$register .. p$register }, $EXTENSION_CLASS_DESCRIPTOR->remap(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p$register
                """.trimIndent()
            },
        )
    }
}
