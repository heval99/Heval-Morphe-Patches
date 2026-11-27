import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PKG = "com.beeper.android"
private const val CLONE = "com.beeper.android.morphe"
private const val VERSION = "4.67.0"
private const val OWN_AUTHORITY = "com.beeper.api"

/**
 * GitHub #179: the "Clone" package name of "GmsCore support" must leave no provider authority,
 * permission, permission group or permission tree of the original app in the manifest (Beeper's own
 * provider uses the authority "com.beeper.api") and must follow the authority in the bytecode.
 */
class BeeperCloneSmokeTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `the clone renames every authority and permission and follows them in the bytecode`() {
        val root = repoRoot()
        val apk = File(root, "apks/beeper/base.apk")
        assumeTrue(apk.exists(), "skipping: base.apk not present at ${apk.path}")

        val patches = loadAllPatches(newestPatchBundle(root))
        val gms = patches.single { it.name == "GmsCore support" && it.compatibility.orEmpty().any { c -> c.packageName == PKG } }
        gms.options.set("packageName", CLONE)

        val classes = applyPatches(
            apk = apk,
            workDir = workDir,
            pkg = PKG,
            version = VERSION,
            patchNames = setOf("GmsCore support"),
            allPatches = patches,
        )

        // 1. Manifest: nothing of the original package or the app's own authority is left.
        val manifestFile = workDir.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && it.readText().contains("package=\"$CLONE\"") }
            .firstOrNull() ?: error("no decoded $CLONE AndroidManifest.xml under $workDir")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        fun values(tag: String, attribute: String) = (0 until document.getElementsByTagName(tag).length)
            .map { (document.getElementsByTagName(tag).item(it) as Element).getAttribute(attribute) }

        val authorities = values("provider", "android:authorities").flatMap { it.split(';') }
        val declared = listOf("permission", "uses-permission", "permission-group", "permission-tree")
            .flatMap { values(it, "android:name") }
        check(authorities.isNotEmpty()) { "the manifest has no provider authority" }
        // The clone name itself starts with "com.beeper.android.", so renamed names are excluded first.
        fun isRenamed(name: String) = name == CLONE || name.startsWith("$CLONE.")
        for (name in authorities + declared) {
            assertTrue(
                isRenamed(name) || (name != PKG && name != OWN_AUTHORITY && !name.startsWith("$PKG.")),
                "old name left behind in the clone manifest: $name",
            )
        }
        assertTrue("$CLONE.$OWN_AUTHORITY" in authorities, "own authority not renamed; authorities: $authorities")
        // Permissions named by a component follow the renamed declarations.
        val used = listOf("android:permission", "android:readPermission", "android:writePermission").flatMap { attr ->
            listOf("provider", "receiver", "service", "activity").flatMap { values(it, attr) }
        }.filter { it.isNotEmpty() }
        assertTrue(used.all { isRenamed(it) || !it.startsWith("$PKG.") }, "component permissions of the old package: $used")

        // 2. Bytecode: MyContentProvider.<clinit> registers the renamed authority and the content URI follows it.
        val provider = classes.singleOrNull { it.type == "Lcom/beeper/api/MyContentProvider;" }
            ?: error("Lcom/beeper/api/MyContentProvider; not found in the emitted dexes")
        val clinit = provider.methods.single { it.name == "<clinit>" }
        val strings = clinit.instructions().mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.toList()
        assertTrue("$CLONE.$OWN_AUTHORITY" in strings, "MyContentProvider.<clinit> strings: $strings")
        assertTrue(OWN_AUTHORITY !in strings, "MyContentProvider.<clinit> still adds the old authority: $strings")
        // The content:// URIs live in other methods of the provider (and the wear bridge), so scan every class.
        val uris = classes.flatMap { cls ->
            cls.methods.flatMap { method ->
                method.implementation?.instructions?.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                }.orEmpty().filter { it.startsWith("content://$OWN_AUTHORITY") || it.startsWith("content://$CLONE.$OWN_AUTHORITY") }
                    .map { "${cls.type}->${method.name}: $it" }
            }
        }
        assertTrue(uris.isNotEmpty(), "no Beeper provider content:// URI found at all")
        assertTrue(uris.none { it.contains(": content://$OWN_AUTHORITY") }, "old content URI left: $uris")
        assertTrue(
            uris.any { it.contains("MyContentProvider;->invokeSuspend") || it.contains("MyContentProvider\$insert") } &&
                uris.any { it.contains(": content://$CLONE.$OWN_AUTHORITY/count/chats?isLowPriority=0&showInAllChats=1") },
            "the provider's and the wear bridge's content:// URIs were not rewritten: $uris",
        )

        // The rest of GmsCore support (spoofing metadata, MicroG check) is asserted by BeeperSmokeTest;
        // assertGmsCoreSupport expects the unchanged package, so it is not reused here.
    }
}
