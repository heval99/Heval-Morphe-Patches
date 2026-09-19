package app.template.patches.unifiedremote.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_UNIFIEDREMOTE

/**
 * Unified Remote's "Full" unlock is driven by RevenueCat. Every premium check ends at
 * `EntitlementInfo.isActive` (the library class keeps its name and method across
 * releases), so forcing it true unlocks Full regardless of the billing backend
 * (Google Play or Amazon).
 */
object EntitlementInfoIsActiveFingerprint : Fingerprint(
    custom = { method, classDef ->
        classDef.type == "Lcom/revenuecat/purchases/EntitlementInfo;" &&
            method.name == "isActive" &&
            method.returnType == "Z" &&
            method.parameters.isEmpty()
    }
)

@Suppress("unused")
val enableProPatch = bytecodePatch(
    name = "Enable Pro",
    description = "Unlocks Unified Remote Full via the RevenueCat entitlement."
) {
    compatibleWith(COMPATIBILITY_UNIFIEDREMOTE)

    execute {
        // Mandatory anchor: if RevenueCat stops shipping this class the patch fails
        // loudly instead of silently doing nothing.
        EntitlementInfoIsActiveFingerprint.method.returnEarly(true)
    }
}
