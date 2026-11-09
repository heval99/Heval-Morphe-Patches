package app.template.patches.sofascore.misc.theme

import app.template.patches.shared.Constants.COMPATIBILITY_SOFASCORE
import app.template.patches.shared.misc.theme.AmoledColor
import app.template.patches.shared.misc.theme.amoledBlackResourcePatch

// AMOLED black (2026-11-09, owner request for the sports apps). Opt-in. Sofascore 26.10.19 keeps its
// dark theme in night-qualified colors: `surface_0` (the page background) is already `#000000` at
// night, `surface_1` (cards) is `#171c1f` and `surface_P` (elevated surfaces) `#272c32`. The AMOLED
// values are `#0a0a0a` for cards and `#141414` for elevated surfaces, so card edges still read on an
// OLED panel. The legacy `sofa*` colors (list and menu backgrounds, `k_1A` and friends) and the
// light theme are left alone. A build-time resource edit, not a Patch settings switch.
@Suppress("unused")
val amoledBlackPatch = amoledBlackResourcePatch(
    COMPATIBILITY_SOFASCORE,
    "res/values-night/colors.xml",
    mapOf(
        "surface_1" to AmoledColor("#ff171c1f", "#ff0a0a0a"),
        "surface_P" to AmoledColor("#ff272c32", "#ff141414"),
    ),
)
