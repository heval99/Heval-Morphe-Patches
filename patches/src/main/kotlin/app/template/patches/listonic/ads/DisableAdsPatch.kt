package app.template.patches.listonic.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_LISTONIC
import app.template.patches.shared.addInstructionChecked
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes all ads."
) {
    compatibleWith(COMPATIBILITY_LISTONIC)

    execute {
        // 1. Listonic's ad layer hides every ad type while its PREMIUM lock is held. The app engages
        // that lock at startup and releases it when RevenueCat reports no premium; releasing never
        // happens now. The PREMIUM lock blocks every ad type, rewarded ones included, so a "watch an
        // ad to earn" offer may still show but its ad never loads.
        val releasedEvent = classDefByOrNull { classDef ->
            classDef.methods.any { toString ->
                toString.name == "toString" && toString.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == LOCK_RELEASED_EVENT_STRING
                } == true
            }
        }
            ?: throw PatchException("Disable ads: LockReleased event class not found")
        val release = lockReleaseFingerprint(releasedEvent.type).matchAllOrNull().orEmpty()
        if (release.size != 1) {
            throw PatchException("Disable ads: expected one lock release method, found ${release.map { it.method.name }}")
        }
        release.single().method.returnEarly()

        // 2. The eager ad provider warm-up (AppLovin, Google Mobile Ads, Amazon APS) has no lock
        // check. Every real ad load still initialises its provider on demand, so skipping the
        // warm-up only keeps the ad SDKs and their traffic from starting.
        InitializeEagerFingerprint.method.returnEarly()

        // 3. AppConfiguration.isAdsEnabled false: the bottom banner composable reads it and then
        // does not reserve its slot.
        AppConfigurationConstructorFingerprint.method.addInstructionChecked(0, "const/4 p5, 0x0")
    }
}
