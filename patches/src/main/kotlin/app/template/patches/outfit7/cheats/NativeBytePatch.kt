package app.template.patches.outfit7.cheats

import app.morphe.patcher.patch.PatchException
import java.io.File

internal const val LIBIL2CPP_ARM64 = "lib/arm64-v8a/libil2cpp.so"

/** ARM64 `adrp`: keeps the opcode and the destination register, drops the page offset (relocation dependent). */
internal const val MASK_ADRP = 0x9F00001FL

/** ARM64 load/store with an unsigned 12-bit offset: keeps the opcode, base and target registers, drops the offset. */
internal const val MASK_LDST_UIMM = 0xFFC003FFL

/** ARM64 `bl`: keeps the opcode, drops the branch distance. */
internal const val MASK_BL = 0xFC000000L

/** Length of the only libil2cpp.so build (26.5.4.9664) these patches were written for. */
internal const val LIBIL2CPP_LENGTH = 48_897_552

/**
 * A search window for one patch site. [window]/[mask] hold the bytes to look for (a mask byte of 0xFF
 * compares that byte, 0x00 ignores it); the match is searched at 4-byte aligned file offsets, which
 * is where ARM64 code sits. The single match must start at the documented absolute file offset
 * [windowOffset] (this is what pins the build, since a masked window could also hit a sibling
 * function elsewhere); [replacement] is then written at [patchAt] inside the window.
 */
internal class NativeSite(
    val label: String,
    val window: ByteArray,
    val mask: ByteArray,
    val patchAt: Int,
    val replacement: ByteArray,
    val windowOffset: Int,
) {
    init {
        require(window.size == mask.size) { "$label: window and mask differ in size" }
        require(patchAt >= 0 && patchAt + replacement.size <= window.size) { "$label: patch lies outside the window" }
    }
}

internal fun hexBytes(hex: String): ByteArray {
    val h = hex.filter { !it.isWhitespace() }
    require(h.length % 2 == 0) { "odd hex length" }
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** One ARM64 instruction as little-endian hex bytes plus the bit mask (see MASK_*) of the bits to compare. */
internal class Insn(val hex: String, val mask: Long = 0xFFFFFFFFL)

internal fun insn(hex: String, mask: Long = 0xFFFFFFFFL) = Insn(hex, mask)

/** Builds a site from instructions. The window bytes are stored masked, so a masked field's value is irrelevant. */
internal fun arm64Site(
    label: String,
    insns: List<Insn>,
    patchInsn: Int,
    replacementHex: String,
    windowOffset: Int,
): NativeSite {
    val window = ByteArray(insns.size * 4)
    val mask = ByteArray(insns.size * 4)
    insns.forEachIndexed { i, insn ->
        val bytes = hexBytes(insn.hex)
        require(bytes.size == 4) { "$label: instruction $i is not 4 bytes" }
        for (b in 0 until 4) {
            val m = ((insn.mask shr (8 * b)) and 0xFF).toInt()
            mask[i * 4 + b] = m.toByte()
            window[i * 4 + b] = (bytes[b].toInt() and m).toByte()
        }
    }
    val replacement = hexBytes(replacementHex)
    for (i in 0 until replacement.size / 4) {
        require(insns[patchInsn + i].mask == 0xFFFFFFFFL) { "$label: a patched instruction must be fully compared" }
    }
    return NativeSite(label, window, mask, patchInsn * 4, replacement, windowOffset)
}

/** File offsets (4-byte aligned) at which [site]'s window matches. */
internal fun findSite(bytes: ByteArray, site: NativeSite): List<Int> {
    val w = site.window
    val m = site.mask
    val hits = ArrayList<Int>(2)
    var i = 0
    val last = bytes.size - w.size
    while (i <= last) {
        var ok = true
        for (j in w.indices) {
            if ((bytes[i + j].toInt() and m[j].toInt()) != w[j].toInt()) {
                ok = false
                break
            }
        }
        if (ok) hits.add(i)
        i += 4
    }
    return hits
}

/**
 * Pins the build and then patches: the library must have [expectedLength] bytes, every site's
 * window must hit exactly once, and that hit must sit at the site's documented file offset (no two
 * sites may touch the same bytes). Only then are the replacements written into a copy of [bytes];
 * nothing is changed when any check fails. Returns the patched copy and the rewritten file offsets.
 */
internal fun applyNativeSites(
    bytes: ByteArray,
    sites: List<NativeSite>,
    libName: String = LIBIL2CPP_ARM64,
    expectedLength: Int = LIBIL2CPP_LENGTH,
): Pair<ByteArray, List<Int>> {
    if (bytes.size != expectedLength) {
        throw PatchException(
            "$libName has ${bytes.size} bytes, expected $expectedLength. " +
                "This library is not the build the patch was written for.",
        )
    }
    val offsets = sites.map { site ->
        val hits = findSite(bytes, site)
        if (hits.size != 1) {
            throw PatchException(
                "Expected exactly one '${site.label}' site in $libName, found ${hits.size}. " +
                    "This library is not the build the patch was written for.",
            )
        }
        if (hits.single() != site.windowOffset) {
            throw PatchException(
                "'${site.label}' in $libName matched at file offset 0x${hits.single().toString(16)}, " +
                    "expected 0x${site.windowOffset.toString(16)}. " +
                    "This library is not the build the patch was written for.",
            )
        }
        hits.single() + site.patchAt
    }
    val ranges = offsets.indices.map { offsets[it] until offsets[it] + sites[it].replacement.size }
    for (a in ranges.indices) for (b in a + 1 until ranges.size) {
        if (ranges[a].first <= ranges[b].last && ranges[b].first <= ranges[a].last) {
            throw PatchException("Patch sites '${sites[a].label}' and '${sites[b].label}' overlap in $libName.")
        }
    }
    val patched = bytes.copyOf()
    sites.forEachIndexed { i, site -> site.replacement.copyInto(patched, offsets[i]) }
    return patched to offsets
}

/** Applies [sites] to the library file; the file is left untouched when a check fails. */
internal fun patchLibil2cpp(lib: File, sites: List<NativeSite>) {
    if (!lib.exists()) {
        throw PatchException("$LIBIL2CPP_ARM64 not found. Patch the arm64 APK, not another split.")
    }
    val (patched, _) = applyNativeSites(lib.readBytes(), sites)
    lib.writeBytes(patched)
}
