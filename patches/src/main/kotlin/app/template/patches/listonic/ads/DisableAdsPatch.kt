package app.template.patches.listonic.ads

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_LISTONIC
import app.template.patches.shared.addInstructionChecked

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes all ads."
) {
    compatibleWith(COMPATIBILITY_LISTONIC)

    execute {
        // 1. Listonic's ad layer hides every ad type while its PREMIUM lock is held. The app engages
        // that lock at startup and releases it when RevenueCat reports no premium; releasing never
        // happens now. The rewarded lock stays held too, so rewarded ads stop as well.
        LockReleaseFingerprint.method.returnEarly()

        // 2. The eager ad provider warm-up (AppLovin, Google Mobile Ads, Amazon APS) has no lock
        // check. Every real ad load still initialises its provider on demand, so skipping the
        // warm-up only keeps the ad SDKs and their traffic from starting.
        InitializeEagerFingerprint.method.returnEarly()

        // 3. AppConfiguration.isAdsEnabled false: the bottom banner composable reads it and then
        // does not reserve its slot.
        AppConfigurationConstructorFingerprint.method.addInstructionChecked(0, "const/4 p5, 0x0")
    }
}
