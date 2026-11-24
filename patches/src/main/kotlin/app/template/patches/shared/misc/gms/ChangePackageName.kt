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
// rename of "GmsCore support". Only the app's own permissions and provider authorities (those
// under "<package>." or equal to it) are renamed; foreign ones are left alone, an @string authority
// throws a PatchException, and the per-app overrides and getPackageName() hooks of the original
// are dropped.

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
 *         string constants that hard-code them (for example a FileProvider authority).
 */
internal fun Document.changePackageName(fromPackageName: String, toPackageName: String): Map<String, String> {
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

    // Provider authorities under the app's package (androidx-startup, fileprovider, ...), including
    // one that is exactly the package name. Authorities are global: a clone that keeps one fails
    // with INSTALL_FAILED_CONFLICTING_PROVIDER next to the original.
    val providers = getElementsByTagName("provider")
    for (i in 0 until providers.length) {
        val provider = providers.item(i) as Element
        val authorities = provider.getAttribute("android:authorities").split(';')
        provider.setAttribute(
            "android:authorities",
            authorities.joinToString(";") { authority ->
                if (authority.startsWith("@")) {
                    // A resource reference cannot be renamed here (the value lives in resources
                    // and the owner is unknown), so fail loudly instead of a clone that cannot install.
                    throw PatchException(
                        "Cannot rename provider authority '$authority' (resource reference) for the clone package",
                    )
                }
                if (authority == fromPackageName) {
                    // Not added to the returned map: the map also rewrites bytecode string constants and
                    // attributes equal to the key, and an exact package-name string is far more common
                    // (identity checks, process names) than an authority hard-coded as the bare package.
                    rename(authority)
                } else if (authority.startsWith(ownPrefix)) {
                    rename(authority).also { renamed[authority] = it }
                } else {
                    authority
                }
            },
        )
    }

    // Every attribute that names a renamed permission: <permission>, <uses-permission>, and
    // android:permission / readPermission / writePermission of components.
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
