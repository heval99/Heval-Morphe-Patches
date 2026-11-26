import app.morphe.patcher.patch.PatchException
import app.template.patches.shared.misc.gms.changePackageName
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

    private fun manifest(extraProvider: String = ""): Document {
        val xml = """
            <manifest package="$pkg">
              <permission-tree android:name="$pkg.permission"/>
              <permission-group android:name="$pkg.permission-group"/>
              <permission android:name="$pkg.permission.A" android:permissionGroup="$pkg.permission-group"/>
              <permission android:name="$pkg.permission.B" android:permissionGroup="$pkg.permission-group"/>
              <uses-permission android:name="$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
              <uses-permission android:name="$foreignPermission"/>
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
        (0 until getElementsByTagName(tag).length).map { (getElementsByTagName(tag).item(it) as Element).getAttribute(attribute) }

    @Test
    fun `renames package, permissions, groups, trees and authorities`() {
        val document = manifest()
        val renamed = document.changePackageName(pkg, clone)

        assertEquals(clone, document.documentElement.getAttribute("package"))

        val declared = listOf("permission", "uses-permission", "permission-group", "permission-tree")
            .flatMap { document.values(it, "android:name") }
        val authorities = document.values("provider", "android:authorities").flatMap { it.split(';') }
        for (name in declared + authorities) {
            if (name == foreignPermission || name == "other.b") continue
            assertFalse(name == pkg || name.startsWith("$pkg."), "old name left behind: $name")
        }
        assertTrue(foreignPermission in declared)
        assertTrue("other.b" in authorities)
        assertEquals(listOf(clone, "$clone.fileprovider").size, authorities.count { it == clone || it == "$clone.fileprovider" })
        assertEquals(
            listOf("$clone.androidx-startup", "$clone.fileprovider", clone, "$clone.a;other.b;$clone.c"),
            document.values("provider", "android:authorities"),
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
    fun `a resource reference authority fails loudly`() {
        val document = manifest("""<provider android:name="P5" android:authorities="@string/provider_authority"/>""")
        val error = assertThrows<PatchException> { document.changePackageName(pkg, clone) }
        assertTrue(error.message!!.contains("@string/provider_authority"))
    }
}
