package app.template.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_SOFASCORE = Compatibility(
        name = "Sofascore", // App name as it appears in the Android launcher.
        packageName = "com.sofascore.results",
        apkFileType = ApkFileType.APK, // Preferred or recommended file type.
        appIconColor = 0x384CF4, // Icon color in Morphe Manager.
        targets = listOf(
            // "version = null" means the patch works with the latest app target
            // and is expected to work with all future app targets.
            //
            // It is highly recommended to always include the exact app version you developed your patches for
            // or the last version you have confirmed as 100% working.
            AppTarget(
                version = "26.07.27"
            ),
            // Verified 2026-08-14: getForceAds/getForceHideAds/getHasServerAds/getHasPremium/
            // getPurchasedAds are all still present unobfuscated on UserAccount in this build,
            // so the existing fingerprints should still match. Not device-tested.
            AppTarget(
                version = "26.08.03"
            )
        )
    )

    val COMPATIBILITY_FOTMOB = Compatibility(
        name = "FotMob",
        packageName = "com.mobilefootie.wc2010",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x00985F,
        targets = listOf(AppTarget(version = null))
    )

    val COMPATIBILITY_MYFITNESSPAL = Compatibility(
        name = "MyFitnessPal",
        packageName = "com.myfitnesspal.android",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x0072BC,
        targets = listOf(AppTarget(version = null))
    )

    val COMPATIBILITY_BOXBOX = Compatibility(
        name = "BoxBox",
        packageName = "club.boxbox.android",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0000,
        targets = listOf(AppTarget(version = null))
    )

    val COMPATIBILITY_SAPHELINK = Compatibility(
        name = "Saphe Link",
        packageName = "my.saphelink",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x000000,
        targets = listOf(AppTarget(version = "6.5.5"))
    )

    val COMPATIBILITY_ANYDESK = Compatibility(
        name = "AnyDesk",
        packageName = "com.anydesk.anydeskandroid",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xEF443B,
        targets = listOf(AppTarget(version = "8.3.4", versionCode = 80304))
    )

    // Verified 2026-08-19 against brave.apkm v1.93.136 (arm64-v8a, Android 12L+).
    // All Brave Origin anchor strings and non-obfuscated classes
    // (BraveOriginPreferences, BraveOriginSettingsLauncherHelper, profiles/Profile)
    // are present. Obfuscated class names rotated vs dh6k's v1.92.140 target
    // (v42 -> ei2, d72 -> hk2, b12 -> ke2, lf2 -> rs2, pc2 -> vp2, lv1 -> t82),
    // but every fingerprint is string-based so resolves at patch time.
    val COMPATIBILITY_BRAVE = Compatibility(
        name = "Brave Browser",
        packageName = "com.brave.browser",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xFF4500,
        targets = listOf(AppTarget(version = "1.93.136"))
    )
}