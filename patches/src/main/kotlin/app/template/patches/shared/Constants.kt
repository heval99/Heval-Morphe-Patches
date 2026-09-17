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
            // Verified 2026-09-17 against com.sofascore.results 26.09.07 (versionCode 260907002,
            // universal, Android 12L+). This build shuffled a lot: the app-level premium gates
            // the old "Enable Premium" fingerprints targeted (aiInsights/removeAds/PremiumToken/
            // isPremium) are gone, FirebaseAnalytics.logEvent was removed, Adjust is no longer
            // bundled and the Facebook/Crashlytics SDK classes are R8-renamed. The patches were
            // re-anchored:
            //   - Disable ads: unchanged; UserAccount getForceAds/getForceHideAds/getHasServerAds
            //     are still unobfuscated.
            //   - Enable Premium: forces UserAccount.getHasPremium() and ProfileData.getHasPremium(),
            //     the two boxed-Boolean flags every premium gate reads.
            //   - Disable telemetry: AppsFlyer Lib subclass logEvent, AppMeasurementSdk.logEvent,
            //     and Crashlytics (located via its collection-enabled SharedPreferences key).
            //   - Disable Facebook SDK: FacebookInitProvider / AudienceNetworkContentProvider onCreate.
            //   - Block marketing notifications: PromotionModal / tennis promo bottom sheet
            //     onViewCreated dismiss the sheet before it renders.
            // The "Disable Play Integrity" patch was dropped: this build does not bundle the
            // Play Integrity classes (the AppsFlyer SDK only references them, behind a catch),
            // so the patch had nothing to patch.
            // All 5 patches apply cleanly; SofascoreSmokeTest asserts the emitted bytecode.
            AppTarget(
                version = "26.09.07",
                versionCode = 260907002
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

    // Verified 2026-08-19 against 365scores.apkm v14.8.8 (universal, Android 7.0+).
    // App uses Google Mobile Ads (AdMob) loaded via the Blaze GAM SDK wrapper.
    // MobileAds.initialize(Landroid/content/Context;)V and the (Context, Listener)
    // overload are both present unobfuscated in com.google.android.gms.ads.MobileAds.
    // Ad SDKs observed: Google Mobile Ads, Unity, InMobi, Vungle, Mintegral,
    // ByteDance Pangle, Meta Audience Network (all GAM-mediated).
    val COMPATIBILITY_365SCORES = Compatibility(
        name = "365Scores",
        packageName = "com.scores365",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xFFC107,
        targets = listOf(AppTarget(version = "14.8.8", versionCode = 1488))
    )

    // Verified 2026-08-20 against livescore.apk v9.9.1 (universal, Android 7.0+).
    // AdsRemovalSettings.areAdsDisabled()Z is the single ad gate checked by
    // BannerManagerImpl, InterstitialAdsUseCase, MpuAdsConfig, HeroPlacementUseCase,
    // CoverageSponsorshipSettings and AnnouncementBannerUseCase. Class and method
    // are un-obfuscated in com.livescore.ads.config.
    val COMPATIBILITY_LIVESCORE = Compatibility(
        name = "Livescore",
        packageName = "com.livescore",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xC8102E,
        targets = listOf(AppTarget(version = "9.9.1", versionCode = 2120))
    )

    // Verified 2026-08-20 against FishBuddy v11.0.84 (universal, Android 7.0+).
    // Premium gate is User.hasPremium()Z (no.fishbuddy.app.data.models.User),
    // reads the server-populated `roles` HashMap for the "premium" entitlement.
    // Subscription managed via RevenueCat (no ad SDKs). Class + method names
    // un-obfuscated.
    val COMPATIBILITY_FISHBUDDY = Compatibility(
        name = "FishBuddy",
        packageName = "no.fishbuddy_playground.app",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x0288D1,
        targets = listOf(AppTarget(version = "11.0.84", versionCode = 110084))
    )

    // Verified 2026-08-20 against ru.iptvremote.android.iptv apkm v9.1.25
    // (universal, Android 12L+). Free app package; separate Pro app
    // (ru.iptvremote.android.iptv.pro) unlocks features when installed.
    // Ad SDK: Wortise mediation (com.wortise.ads.WortiseSdk) wrapping AppLovin,
    // Yandex Mobile Ads, Google Mobile Ads. Pro/trial gate is
    // IptvFreeApplication.k()Z (R8-renamed; signature-identified).
    val COMPATIBILITY_IPTVREMOTE = Compatibility(
        name = "IPTV",
        packageName = "ru.iptvremote.android.iptv",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x1976D2,
        targets = listOf(AppTarget(version = "9.1.25", versionCode = 5010))
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

    // Verified 2026-08-27 against librepods_1.0.0-rc1-play-63 .apkm from APKMirror
    // (arm64-v8a + x86, Android 16+ / minSdk 36). App logic is R8-obfuscated into
    // top-level classes (e.g. PlayBillingProvider -> uo) around native protocol
    // libs (libbluetooth_socket.so, libl2c_fcr_hook.so). Premium gates all read
    // PurchaseUiState.isPremium which is fed solely by PlayBillingProvider's
    // _isPremium StateFlow written in processPurchases(); a FOSS variant of the
    // provider also ships in this build. In-app product:
    // "librepods.advanced_features.v2". No license server (upstream GPL-3.0,
    // github.com/librepods-org/librepods).
    val COMPATIBILITY_LIBREPODS = Compatibility(
        name = "LibrePods",
        packageName = "me.kavishdevar.librepods",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x406C5C,
        targets = listOf(
            AppTarget(
                version = "1.0.0-rc1-play",
                versionCode = 63
            )
        )
    )

    // Verified 2026-08-30 against easynotes..._1.3.59.0819-10565 .apkm from APKMirror
    // (arm64-v8a + armeabi-v7a, Android 6.0+ / minSdk 23). App code is NOT obfuscated:
    // 3,589 readable classes under notes.easy.android.mynotes.
    //
    // Entitlement is entirely local — there is no license server. Everything routes through
    // three public static booleans on notes.easy.android.mynotes.App:
    //   isVip()Z       38 call sites - master gate for premium assets (fonts, backgrounds,
    //                  stickers, drawing tools). Per-asset flags such as DrawPatternRes.isVip()
    //                  are only consulted *inside* an `if (!App.isVip())` check.
    //   isAdFree()Z    4 call sites  - gates banner loading in utils/AdManager
    //                  (e.g. the "notes_edit_banner" unit) and App$AppOpenAdManager.
    //   isGoogleVip()Z drives the "Google VIP" badge only.
    //
    // Both isVip() and isGoogleVip() reduce to
    //   userConfig.getHasBuyed() || userConfig.getHasSubscribe()
    // with isVip() additionally early-outing on getBillingMonthlyTestOpen() (false) and
    // isReferralVip() (true). State lives in SharedPreferences via constant/UserConfig
    // (keys has_buyed, has_subscribe, has_m_subscribe, has_y_subscribe).
    //
    // Important: billing/BillingManager runs queryPurchaseState() on startup and its
    // callbacks (BillingManager$5/$7/$8) write those prefs back from whatever Play reports,
    // so a non-payer gets them reset to false. That is why we patch the *consumers*
    // (App.isVip/isAdFree) rather than the writers — forced return values ignore the reset.
    //
    // SKUs: BILLING_MONTH / BILLING_YEAR / BILLING_LIFETIME / BILLING_MONTH_TO_YEAR /
    // BILLING_UP_TO_LIFETIME (analytics suffixes "1m", "1y", "lifetime").
    // Ad SDKs: Google Mobile Ads (app-open), Meta Audience Network, PubMatic.
    val COMPATIBILITY_EASYNOTES = Compatibility(
        name = "EasyNotes",
        packageName = "easynotes.notes.notepad.notebook.privatenotes.note",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x00ACFF,
        targets = listOf(
            AppTarget(
                version = "1.3.59.0819",
                versionCode = 10565
            )
        )
    )
}