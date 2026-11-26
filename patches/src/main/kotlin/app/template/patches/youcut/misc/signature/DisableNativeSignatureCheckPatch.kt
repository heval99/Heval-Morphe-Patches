package app.template.patches.youcut.misc.signature

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_YOUCUT
import java.util.logging.Logger

private const val LIB_ARM64 = "lib/arm64-v8a/libisvideoengine.so"
private const val LIB_V7A = "lib/armeabi-v7a/libisvideoengine.so"

// mov w1, #9 (SIGKILL), little-endian; the next instruction is bl kill@plt.
private val MOV_W1_SIGKILL = byteArrayOf(0x21, 0x01, 0x80.toByte(), 0x52)
private val NOP = byteArrayOf(0x1F, 0x20, 0x03, 0xD5.toByte())

private fun ByteArray.indexOfSequence(sequence: ByteArray, start: Int = 0): Int {
    outer@ for (i in start..size - sequence.size) {
        for (j in sequence.indices) {
            if (this[i + j] != sequence[j]) continue@outer
        }
        return i
    }
    return -1
}

/**
 * YouCut's video engine (`libisvideoengine.so`) verifies the APK signing certificate and checks
 * for a debugger in its load path; when either check fails it calls `kill(getpid(), SIGKILL)`
 * (SIGKILL, so there is no Java exception). A patched, re-signed build therefore died on the
 * first action that loads the engine (new project, templates, AI), which is why the app was
 * dropped in 2026-10-09. Morphe can rewrite a native library with a rawResourcePatch, so the
 * single `bl kill` call is replaced with a NOP: the gate's checks still run, the process simply
 * keeps going and the function returns 1.
 *
 * The anchor is the SIGKILL setup, `mov w1, #9; bl <kill>`, which is unique in the library for
 * this version. If the sequence is missing or ambiguous the patch fails instead of shipping a
 * build that still kills itself. Only the arm64 library is patched; an APK that also carries
 * armeabi-v7a copies (APKMirror bundles) is accepted and those copies are left as they are,
 * since a 64-bit device never loads them. 32-bit-only devices are unsupported.
 */
@Suppress("unused")
val disableNativeSignatureCheckPatch = rawResourcePatch(
    name = "Disable native signature check",
    description = "Skips the native check that kills the app when it is re-signed.",
) {
    compatibleWith(COMPATIBILITY_YOUCUT)

    execute {
        // Multi-ABI bundles (#190) also carry armeabi-v7a copies. A 64-bit device loads the arm64
        // directory only, so those copies are left untouched (never loaded there). 32-bit-only
        // devices would still hit the kill and stay unsupported.
        val v7a = runCatching { get(LIB_V7A) }.getOrNull()
        if (v7a != null && v7a.exists()) {
            Logger.getLogger("YouCut").info(
                "$LIB_V7A left unpatched: only $LIB_ARM64 is patched, 32-bit-only devices are unsupported.",
            )
        }

        val lib = get(LIB_ARM64)
        if (!lib.exists()) {
            throw PatchException("$LIB_ARM64 not found. Patch the arm64 APK, not another split.")
        }

        val bytes = lib.readBytes()
        val hits = buildList {
            var i = bytes.indexOfSequence(MOV_W1_SIGKILL)
            while (i >= 0) {
                // The instruction right after the SIGKILL setup must be a BL.
                if (i + 8 <= bytes.size && (bytes[i + 7].toInt() and 0xFC) == 0x94) add(i)
                i = bytes.indexOfSequence(MOV_W1_SIGKILL, i + 1)
            }
        }
        val hit = hits.singleOrNull()
            ?: throw PatchException(
                "Expected exactly one 'mov w1, #9; bl' SIGKILL call in $LIB_ARM64, found ${hits.size}.",
            )

        NOP.copyInto(bytes, hit + 4)
        lib.writeBytes(bytes)
    }
}
