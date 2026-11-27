import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PKG = "com.scores365"
private const val CLONE = "com.scores365.morphe"

/**
 * The "Clone" package name of "GmsCore support" on 365Scores: its FileProvider authorities are
 * @string references (renamed in strings.xml), and the <queries><provider> entries naming Facebook's
 * providers must stay untouched.
 */
class Scores365CloneSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `the clone renames app providers, resource authorities and leaves queries alone`() {
        val root = repoRoot()
        val apk = File(root, "apks/365scores/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val patches = loadAllPatches(newestPatchBundle(root))
        patches.single { it.name == "GmsCore support" && it.compatibility.orEmpty().any { c -> c.packageName == PKG } }
            .options.set("packageName", CLONE)

        applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = "15.0.2",
            patchNames = setOf("GmsCore support"),
            allPatches = patches,
        )

        val manifestFile = workDir.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && it.readText().contains("package=\"$CLONE\"") }
            .firstOrNull() ?: error("no decoded $CLONE AndroidManifest.xml under $workDir")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val providers = (0 until document.getElementsByTagName("provider").length)
            .map { document.getElementsByTagName("provider").item(it) as Element }
        val (queried, own) = providers.partition { it.parentNode.nodeName == "queries" }

        // <queries>: Facebook's providers, untouched.
        assertEquals(
            setOf("com.facebook.katana.provider.PlatformProvider", "com.facebook.orca.provider.PlatformProvider"),
            queried.map { it.getAttribute("android:authorities") }.toSet(),
        )

        // <application>: every authority is renamed or a string reference.
        val authorities = own.map { it.getAttribute("android:authorities") }
        assertTrue(authorities.size > 5, "providers: $authorities")
        val references = authorities.filter { it.startsWith("@string/") }
        assertEquals(2, references.size, "the two FileProvider authorities are string references: $authorities")
        for (authority in authorities - references.toSet()) {
            assertTrue(authority.startsWith("$CLONE."), "authority not renamed: $authority")
        }

        // The referenced strings hold the clone authorities.
        val names = references.map { it.removePrefix("@string/") }
        val values = names.map { name ->
            workDir.walkTopDown()
                .filter { it.name == "strings.xml" && it.readText().contains("name=\"$name\"") }
                .map { file ->
                    val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    (0 until strings.getElementsByTagName("string").length)
                        .map { strings.getElementsByTagName("string").item(it) as Element }
                        .first { it.getAttribute("name") == name }.textContent
                }.toList()
        }
        assertEquals(listOf(listOf("$CLONE.fileprovider"), listOf("$CLONE.initfileprovider")).sortedBy { it[0] }, values.sortedBy { it[0] })
    }
}
