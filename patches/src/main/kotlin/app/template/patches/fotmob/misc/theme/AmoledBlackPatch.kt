package app.template.patches.fotmob.misc.theme

import app.template.patches.shared.Constants.COMPATIBILITY_FOTMOB
import app.template.patches.shared.misc.theme.AmoledAttribute
import app.template.patches.shared.misc.theme.amoledBlackStylePatch

// AMOLED black (2026-11-09, owner request for the sports apps). Opt-in. FotMob 241 builds its dark
// theme as the night variant of Base.Theme.FotMob.DayNight: a list of theme attributes that point at
// named grays. The page background (appBackground, window background) is already `@color/black`;
// the cards and Material surfaces are `nero_black` (#1d1d1d), the bottom bar `chinese_black`
// (#141414), the card headers and unselected chips `raisin_black` (#262626) and the bottom sheets
// `dark_charcoal` (#333333). Those grays are shared color names (they also colour other things), so
// the patch re-points ten attributes inside the night theme at AMOLED values (#0a0a0a cards and
// surfaces, #000000 bottom bar, #141414 headers and chips, #121212 sheets) and leaves the color
// resources, the light theme, dividers and texts alone. A build-time resource edit, not a Patch
// settings switch. Not covered: screens that draw hard-coded grays or images.
@Suppress("unused")
val amoledBlackPatch = amoledBlackStylePatch(
    COMPATIBILITY_FOTMOB,
    "res/values-night/styles.xml",
    "Base.Theme.FotMob.DayNight",
    mapOf(
        "cardBackgroundColor" to AmoledAttribute("@color/nero_black", "#ff0a0a0a"),
        "colorSurface" to AmoledAttribute("@color/nero_black", "#ff0a0a0a"),
        "colorSurfaceContainer" to AmoledAttribute("@color/nero_black", "#ff0a0a0a"),
        "colorSurfaceContainerLow" to AmoledAttribute("@color/nero_black", "#ff0a0a0a"),
        "colorSurfaceContainerHigh" to AmoledAttribute("@color/nero_black", "#ff0a0a0a"),
        "colorSurfaceContainerHighest" to AmoledAttribute("@color/nero_black", "#ff0a0a0a"),
        "bottomNavBackgroundColor" to AmoledAttribute("@color/chinese_black", "#ff000000"),
        "cardInCardHeaderBackground" to AmoledAttribute("@color/raisin_black", "#ff141414"),
        "chipBackgroundUnselected" to AmoledAttribute("@color/raisin_black", "#ff141414"),
        "bottomSheetBackgroundColor" to AmoledAttribute("@color/dark_charcoal", "#ff121212"),
    ),
)
