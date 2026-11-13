package app.template.patches.actionlauncher.premium

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

// Action Launcher's Plus state is a client-side integer: the billing flow maps the purchased SKUs
// (al3_plus, al_plus_ex, ...) onto one purchase code and one `() -> boolean` method answers whether
// the stored code equals the Plus code 0x10382. The class is R8-renamed, the code is not, so the
// method is anchored on that literal and must match exactly once.
internal const val PLUS_PURCHASE_CODE = 0x10382

internal object PlusStateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction is NarrowLiteralInstruction && instruction.narrowLiteral == PLUS_PURCHASE_CODE
        } == true
    },
)
