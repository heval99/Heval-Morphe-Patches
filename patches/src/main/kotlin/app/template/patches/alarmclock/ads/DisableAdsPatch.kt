package app.template.patches.alarmclock.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_ALARMCLOCK

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Turns on the ad-free entitlement so Alarm Clock Xtreme shows no banner, " +
        "interstitial or consent ads. Only the ad-free feature is changed; the other shop " +
        "features keep their purchase state."
) {
    compatibleWith(COMPATIBILITY_ALARMCLOCK)

    execute {
        // Entitlement check: if the requested feature is AD_FREE, report it owned. Everything
        // else falls through to the original shop-list lookup.
        ShopEntitlementFingerprint.method.addInstructionsWithLabels(
            0,
            """
                const-string v0, "AD_FREE"
                invoke-virtual {p1}, Lcom/alarmclock/xtreme/shop/data/ShopFeature;->name()Ljava/lang/String;
                move-result-object v1
                invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original
                const/4 v0, 0x1
                return v0
                :original
                nop
            """,
        )
    }
}
