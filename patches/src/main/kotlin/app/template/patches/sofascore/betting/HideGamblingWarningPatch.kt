package app.template.patches.sofascore.betting

import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.featurePatch
import app.template.patches.shared.hevalcontrols.hevalReturnEarly
import app.template.patches.sofascore.controls.SofascoreControlsCategory
import app.template.patches.sofascore.controls.sofascoreControls

internal val HIDE_GAMBLING_WARNING = HevalFeature(
    id = "sofascore.hide_gambling_popup",
    name = "Hide gambling warning pop-up",
    description = "Skips the responsible-gambling pop-up (Spain) when you open a match with odds.",
    category = SofascoreControlsCategory.BETTING,
    // A regulatory notice: stock until the user turns it off on purpose.
    default = false,
)

// Opt-in: the app shows the warning exactly as before until the switch is turned on.
@Suppress("unused")
val hideGamblingWarningPatch = sofascoreControls.featurePatch(
    HIDE_GAMBLING_WARNING,
    default = false,
) {
    val showWarning = SpainGamblingWarningFingerprint.method

    // 1. The single place that creates SpainGamblingMessageDialog (Spain only). Its four callers
    //    (odds fragment, two basketball event pages, a Compose resume effect) all return right
    //    after the call and never wait for the dialog, so leaving early cannot strand a screen.
    showWarning.hevalReturnEarly(HIDE_GAMBLING_WARNING.id)
}
