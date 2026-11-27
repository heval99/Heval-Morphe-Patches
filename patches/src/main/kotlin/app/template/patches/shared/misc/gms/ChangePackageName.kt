/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/patches/all/misc/packagename/ChangePackageNamePatch.kt
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

// Modified for Heval's Morphe Patches (2026-10-11): derived from the manifest part of
// app.morphe.patches.all.misc.clone.cloneAppPatch. A plain function used by the optional package
// rename of "GmsCore support". Only the app's own permissions (those under "<package>.") are
// renamed; foreign permissions are left alone. Every provider authority in the manifest is renamed
// (2026-11-27, GitHub #179): authorities are global and every <provider> of the app's own manifest is
// owned by it, so one outside "<package>." (Beeper's "com.beeper.api") gets "<new package>." in front.
// Only <provider> elements directly under <application> count (<queries><provider> names other apps').
// An @string/<name> authority (365Scores' FileProviders) is renamed in the string resource itself with
// the same rules; one that cannot be resolved throws a PatchException. Known limit: authorities the app
// builds at runtime from the app id (com.facebook.app.FacebookContentProvider<appId>) are renamed in
// the manifest but not in code, so Facebook share-by-content-provider may not work in a clone; the
// install conflict wins. The per-app overrides and getPackageName() hooks of the original are dropped.

package app.template.patches.shared.misc.gms

import app.morphe.patcher.patch.PatchException
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Changes the manifest package from [fromPackageName] to [toPackageName], plus the app's own
 * custom permissions and content provider authorities, so the renamed app can be installed next
 * to the original one.
 *
 * @return Every renamed permission and authority (old to new), so the bytecode patch can rewrite
 *         string constants that hard-code them (for example a FileProvider authority). The bare
 *         package name used as an authority is renamed in the manifest but left out of this map.
 * @param renamedAuthorities Receives every renamed provider authority (old to new, the bare package
 *        name included), so the bytecode patch can also rewrite `content://<authority>/...` strings.
 */
internal fun Document.changePackageName(
    fromPackageName: String,
    toPackageName: String,
    renamedAuthorities: MutableMap<String, String> = mutableMapOf(),
    // Applies `rename` to the value of the named string resource in every res/values*/strings.xml
    // that defines it and returns whether any did; null when the caller has no resources.
    rewriteStringResource: ((name: String, rename: (String) -> String) -> Boolean)? = null,
): Map<String, String> {
    val ownPrefix = "$fromPackageName."
    fun rename(value: String) = toPackageName + value.substring(fromPackageName.length)

    documentElement.setAttribute("package", toPackageName)

    val renamed = mutableMapOf<String, String>()

    // Custom permissions declared or used under the app's package
    // (<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, <package>.permission.C2D_MESSAGE, ...).
    // Permission groups and trees are owned per package too: a clone that keeps
    // "<package>.permission-group" fails to install next to the original with
    // "attempting to redeclare permission group ... already owned" (GitHub #179, Beeper).
    // android:permissionGroup attributes are rewritten by the attribute pass below.
    for (tag in listOf("permission", "uses-permission", "permission-group", "permission-tree")) {
        val elements = getElementsByTagName(tag)
        for (i in 0 until elements.length) {
            val name = (elements.item(i) as Element).getAttribute("android:name")
            if (name.startsWith(ownPrefix)) renamed[name] = rename(name)
        }
    }

    // Every provider authority (androidx-startup, fileprovider, ... and the app's own ones such as
    // Beeper's "com.beeper.api"). Authorities are global: a clone that keeps one fails with
    // INSTALL_FAILED_CONFLICTING_PROVIDER next to the original.
    fun renameAuthority(authority: String): String = when {
        authority.isEmpty() -> authority
        // Not added to the returned map (only to renamedAuthorities): the map also rewrites bytecode
        // string constants and attributes equal to the key, and an exact package-name string is far
        // more common (identity checks, process names) than an authority hard-coded as the bare package.
        authority == fromPackageName -> rename(authority).also { renamedAuthorities[authority] = it }
        authority.startsWith(ownPrefix) -> rename(authority).also { renamed[authority] = it; renamedAuthorities[authority] = it }
        else -> "$toPackageName.$authority".also { renamed[authority] = it; renamedAuthorities[authority] = it }
    }

    // Resource references (@string/name) are renamed in the string resource itself, once per name.
    val renamedStrings = mutableSetOf<String>()
    fun renameReference(reference: String): String {
        val name = reference.removePrefix("@string/").removePrefix("@")
        val rewrite = rewriteStringResource
        if (!reference.startsWith("@string/") || rewrite == null) {
            throw PatchException(
                "Cannot rename provider authority '$reference' (resource reference) for the clone package",
            )
        }
        if (renamedStrings.add(name)) {
            val found = rewrite(name) { value -> value.split(';').joinToString(";") { renameAuthority(it) } }
            if (!found) {
                throw PatchException(
                    "Cannot rename provider authority '$reference': string '$name' not found in res/values*/strings.xml",
                )
            }
        }
        return reference
    }

    // Only the app's own <provider> elements (parent <application>): <queries><provider> names
    // another app's provider for package visibility and must stay as it is.
    val providers = getElementsByTagName("provider")
    for (i in 0 until providers.length) {
        val provider = providers.item(i) as Element
        if (provider.parentNode?.nodeName != "application") continue
        val authorities = provider.getAttribute("android:authorities").split(';')
        provider.setAttribute(
            "android:authorities",
            authorities.joinToString(";") { authority ->
                if (authority.startsWith("@")) renameReference(authority) else renameAuthority(authority)
            },
        )
    }

    // Every attribute whose whole value is a renamed permission or authority (old to new in
    // [renamed]): <permission>, <uses-permission>, android:permission / readPermission /
    // writePermission of components, and any other attribute equal to a renamed foreign
    // authority. This pass covers the whole manifest, <queries> included, so a <queries>
    // entry equal to one of the app's own foreign authorities would be renamed too.
    val elements = getElementsByTagName("*")
    for (i in 0 until elements.length) {
        val attributes = elements.item(i).attributes ?: continue
        for (j in 0 until attributes.length) {
            val attribute = attributes.item(j)
            renamed[attribute.nodeValue]?.let { attribute.nodeValue = it }
        }
    }

    return renamed
}
