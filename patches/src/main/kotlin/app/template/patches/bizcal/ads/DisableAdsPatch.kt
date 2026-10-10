package app.template.patches.bizcal.ads

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_BUSINESSCALENDAR

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Turns off Business Calendar 2's ad-serving gate, so the mediation SDKs never " +
        "start and no interstitial is shown. Pro features are not changed."
) {
    compatibleWith(COMPATIBILITY_BUSINESSCALENDAR)

    execute {
        ShowAdsForUserFingerprint.method.returnEarly(false)
    }
}
