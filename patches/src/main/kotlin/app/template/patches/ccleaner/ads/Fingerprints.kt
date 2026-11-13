package app.template.patches.ccleaner.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// BaseAdService.areAdsAllowed() is the app's own ad switch: it asks the premium boolean first and
// otherwise the remote advertising setting. The method name and the premium method name are
// Avast's private-use glyphs, so the call to the no-arg premium boolean is the anchor.
internal object AdsAllowedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type == "Lcom/avast/android/cleaner/advertisement/BaseAdService;" &&
            method.implementation != null &&
            method.implementation!!.instructions.any { insn ->
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass ==
                    "Lcom/avast/android/cleaner/subscription/premiumService/PremiumService;" &&
                    ref.name == "\u02cd" &&
                    ref.returnType == "Z" &&
                    ref.parameterTypes.isEmpty()
            }
    },
)
