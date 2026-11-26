package app.template.patches.truecaller.misc.nags

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

internal const val BANNER_TYPE = "Lcom/truecaller/settings/impl/ui/categories/banner/BannerType;"

// The Settings banner picker (qj30.a in 26.45.5): the only method returning the kept BannerType enum.
// It asks a ()Z check (Android 11+, CALL_SCREENING role available and not held; the check is the
// first call, right before the CALLER_ID constant) for the "Set Truecaller as your default caller ID
// app" banner, then falls through to the default dialer, draw over other apps and battery banners.
internal object CallerIdBannerPickerFingerprint : Fingerprint(
    returnType = BANNER_TYPE,
    parameters = listOf(),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf(), returnType = "Z"),
        fieldAccess(opcode = Opcode.SGET_OBJECT, definingClass = BANNER_TYPE, name = "CALLER_ID"),
    ),
)
