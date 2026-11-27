import app.morphe.patcher.patch.PatchException
import app.template.patches.shared.misc.gms.changePackageName
import app.template.patches.shared.misc.gms.renamedAuthorityUriReplacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure manifest test (no APK) for the clone rename used by "GmsCore support": permissions, groups,
 * trees, authorities (including the bare package name) and every attribute naming them.
 */
class ChangePackageNameTest {
    private val pkg = "com.example.chat"
    private val clone = "org.cloned.chat"
    private val foreignPermission = "com.other.permission.ACCOUNT_MIGRATION"
    private val action = "$pkg.TOGGLE_INCOGNITO_MODE"

    private fun manifest(extraProvider: String = """<provider android:name="P6" android:authorities="com.example.api" android:readPermission="$pkg.permission.A"/>"""): Document {
        val xml = """
            <manifest package="$pkg">
              <permission-tree android:name="$pkg.permission"/>
              <permission-group android:name="$pkg.permission-group"/>
              <permission android:name="$pkg.permission.A" android:permissionGroup="$pkg.permission-group"/>
              <permission android:name="$pkg.permission.B" android:permissionGroup="$pkg.permission-group"/>
              <uses-permission android:name="$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
              <uses-permission android:name="$foreignPermission"/>
              <queries><provider android:authorities="x.y.PlatformProvider"/></queries>
              <application>
                <provider android:name="P1" android:authorities="$pkg.androidx-startup"/>
                <provider android:name="P2" android:authorities="$pkg.fileprovider" android:permission="$pkg.permission.A"/>
                <provider android:name="P3" android:authorities="$pkg"/>
                <provider android:name="P4" android:authorities="$pkg.a;other.b;$pkg.c"/>
                $extraProvider
                <receiver android:name="R"><intent-filter><action android:name="$action"/></intent-filter></receiver>
              </application>
            </manifest>
        """.trimIndent()
        val factory = DocumentBuilderFactory.newInstance() // not namespace-aware: "android:name" stays a plain name
        return factory.newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun Document.values(tag: String, attribute: String) =
        (0 until getElementsByTagName(tag).length).map { (getElementsByTagName(tag).item(it) as Element) }
            .filter { !(tag == "provider" && it.parentNode.nodeName == "queries") } // <queries> entries are checked on their own
            .map { it.getAttribute(attribute) }

    @Test
    fun `renames package, permissions, groups, trees and authorities`() {
        val document = manifest()
        val authorityMap = mutableMapOf<String, String>()
        val renamed = document.changePackageName(pkg, clone, authorityMap)

        assertEquals(clone, document.documentElement.getAttribute("package"))

        val declared = listOf("permission", "uses-permission", "permission-group", "permission-tree")
            .flatMap { document.values(it, "android:name") }
        val authorities = document.values("provider", "android:authorities").flatMap { it.split(';') }
        for (name in declared) {
            if (name == foreignPermission) continue
            assertFalse(name == pkg || name.startsWith("$pkg."), "old name left behind: $name")
        }
        // Every authority is renamed, the app's own foreign-looking ones too (Beeper's "com.beeper.api").
        for (name in authorities) {
            assertFalse(name == pkg || name.startsWith("$pkg.") || name == "other.b" || name == "com.example.api", "old authority left behind: $name")
        }
        assertTrue(foreignPermission in declared)
        assertEquals(
            listOf("$clone.androidx-startup", "$clone.fileprovider", clone, "$clone.a;$clone.other.b;$clone.c", "$clone.com.example.api"),
            document.values("provider", "android:authorities"),
        )
        // <queries><provider> names another app's provider and stays as it is.
        val queried = document.getElementsByTagName("queries").item(0).firstChild as Element
        assertEquals("x.y.PlatformProvider", queried.getAttribute("android:authorities"))
        assertFalse("x.y.PlatformProvider" in authorityMap)
        assertEquals("$clone.com.example.api", renamed["com.example.api"])
        assertEquals("$clone.other.b", renamed["other.b"])
        assertEquals(
            mapOf(
                "$pkg.androidx-startup" to "$clone.androidx-startup",
                "$pkg.fileprovider" to "$clone.fileprovider",
                pkg to clone,
                "$pkg.a" to "$clone.a",
                "other.b" to "$clone.other.b",
                "$pkg.c" to "$clone.c",
                "com.example.api" to "$clone.com.example.api",
            ),
            authorityMap,
        )

        // The intent action is not a permission or authority and must stay.
        assertEquals(action, document.values("action", "android:name").single())

        // Every permissionGroup points at a declared (renamed) group.
        val groups = document.values("permission-group", "android:name").toSet()
        assertEquals(setOf("$clone.permission-group"), groups)
        val usedGroups = document.values("permission", "android:permissionGroup")
        assertEquals(2, usedGroups.size)
        assertTrue(usedGroups.all { it in groups })

        // Components that name a renamed permission follow it.
        assertEquals("$clone.permission.A", document.values("provider", "android:permission").first { it.isNotEmpty() })

        assertEquals("$clone.permission", renamed["$pkg.permission"])
        assertEquals("$clone.permission-group", renamed["$pkg.permission-group"])
        assertEquals("$clone.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", renamed["$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"])
        assertEquals("$clone.androidx-startup", renamed["$pkg.androidx-startup"])
        assertEquals(null, renamed[pkg], "the bare package authority is renamed in the manifest but kept out of the bytecode rewrite map")
        assertFalse(foreignPermission in renamed)
        assertFalse(action in renamed)
    }

    @Test
    fun `content uris of a renamed authority are rewritten`() {
        val map = mapOf("com.example.api" to "$clone.com.example.api")
        val new = "$clone.com.example.api"
        assertEquals("content://$new", renamedAuthorityUriReplacement("content://com.example.api", map))
        assertEquals("content://$new/count/chats?x=1", renamedAuthorityUriReplacement("content://com.example.api/count/chats?x=1", map))
        assertEquals("content://$new?x=1", renamedAuthorityUriReplacement("content://com.example.api?x=1", map))
        assertEquals("content://$new#f", renamedAuthorityUriReplacement("content://com.example.api#f", map))
        assertEquals(null, renamedAuthorityUriReplacement("content://com.example.apix/a", map))
        assertEquals(null, renamedAuthorityUriReplacement("https://com.example.api/a", map))
        assertEquals(null, renamedAuthorityUriReplacement("content://com.example.api/a", emptyMap()))
    }

    @Test
    fun `a resource reference authority is renamed in its string resource`() {
        val document = manifest(
            """<provider android:name="P5" android:authorities="@string/own"/>
               <provider android:name="P7" android:authorities="@string/foreign"/>
               <provider android:name="P8" android:authorities="@string/own"/>""",
        )
        val strings = mutableMapOf("own" to "$pkg.fileprovider", "foreign" to "com.other.files", "unused" to "$pkg.x")
        val calls = mutableListOf<String>()
        val authorityMap = mutableMapOf<String, String>()
        val renamed = document.changePackageName(pkg, clone, authorityMap) { name, rename ->
            calls += name
            strings[name]?.let { strings[name] = rename(it); true } ?: false
        }
        assertEquals("$clone.fileprovider", strings["own"])
        assertEquals("$clone.com.other.files", strings["foreign"])
        assertEquals("$pkg.x", strings["unused"])
        assertEquals(listOf("own", "foreign"), calls, "each string is rewritten once")
        assertEquals("$clone.fileprovider", authorityMap["$pkg.fileprovider"])
        assertEquals("$clone.com.other.files", renamed["com.other.files"])
        assertEquals(listOf("@string/own", "@string/foreign", "@string/own"), document.values("provider", "android:authorities").takeLast(3))
    }

    @Test
    fun `an unresolvable resource reference authority fails`() {
        val document = manifest("""<provider android:name="P5" android:authorities="@string/missing"/>""")
        assertThrows<PatchException> { document.changePackageName(pkg, clone) { _, _ -> false } }
    }

    @Test
    fun `a resource reference authority fails loudly`() {
        val document = manifest("""<provider android:name="P5" android:authorities="@string/provider_authority"/>""")
        val error = assertThrows<PatchException> { document.changePackageName(pkg, clone) }
        assertTrue(error.message!!.contains("@string/provider_authority"))
    }
}
