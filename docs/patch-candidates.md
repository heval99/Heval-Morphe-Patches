# Patch candidates - save for later

Shortlist of apps worth patching next, re-checked 2026-10-06 against the 926-package /
215-bundle community aggregate (`community_coverage.json`, rebuilt from
morphe-patches.software's `data/bundles.json`). None of the open candidates below are
covered by other bundles, and none are on `docs/patch-blocklist.md`.

Before starting one, follow the normal workflow in `AGENTS.md`: download the latest APK,
triage for PairIP/shields/server-side gates, find a stable anchor, then patch + smoke
test + decompile-verify.

## Tier 1 - ads only, easiest wins (GMA/AppLovin init+load kills)

| App | Package | Latest seen | Notes |
|---|---|---|---|
| Shazam | `com.shazam.android` | 16.62.0 | **Shipped 2026-10-05** - "Disable telemetry" (FirebaseAnalytics.logEvent + public Crashlytics surface). No ad SDK, billing or shields in the dex; no premium gate (free app) |
| Simple Radio | `com.streema.simpleradio` | 6.2.0 | **Shipped 2026-09-29** - "Enable Premium" (local `iab_premium` pref gate + `isPremium()`) and "Disable ads" (GMA init + MAX interstitial load/show) |
| WiFi Analyzer | `com.farproc.wifi.analyzer` | 3.10.5-L | **Shipped 2026-09-29** - "Disable ads" (single `Settings` show-ad gate on the `next_show_ad_time_millisec` pref) |
| Flud Torrent | `com.delphic.flud` | ? | Free version ad-supported, paid listing is the same app ad-free - classic ad-kill. Uncovered 2026-10-06 |
| Moon+ Reader | `com.flyersoft.moonreader` | 9.9 | The classic: very requested app; still uncovered 2026-10-06. Ads in the free version are killable; if the Pro gate is only a separate paid listing, ship ads-only |
| SoundHound | `com.melodis.soundhound.android` | ? | Sister app to our Shazam patch; ads in the free tier. Uncovered 2026-10-06 |
| Dictionary.com | `com.dictionary` | ? | Ad-heavy free version. Uncovered 2026-10-06 |
| CX File Explorer | `com.cx.fileexplorer` | ? | Popular, ad-supported. Uncovered 2026-10-06 |

## Tier 2 - ads + local Pro/Premium

| App | Package | Latest seen | Notes |
|---|---|---|---|
| Pi Music Player | `com.Project100Pi.themusicplayer` | 3.2.0.0 | **Shipped 2026-09-29** - "Enable Premium" (static flag + 5-list in R8 holder; a()/b() forced, temp-ad-free resetter neutered, flag seeded in clinit) and "Disable ads" (GMA init/loads + MAX interstitial load/show) |
| TuneIn Radio | `tunein.player` | 42.5 | **Blocked 2026-10-05** - full PairIP shield; see blocklist |
| Podcast Republic | `com.podcast.podcasts` | 9.17.0 | **Shipped 2026-10-05** - "Disable ads" (GMA init/loads, MAX load/show, Meta AN + InMobi init). No premium gate: ad-free is Firebase invite state only |
| Castbox | `fm.castbox.audiobook.radio.podcast` | 11.26.1 | **Shipped 2026-10-05** - "Disable ads" (GMA init/loads, MAX load/show, Meta AN + InMobi init). No premium gate: vip lists are server-synced, purchases RSA-verified |
| Smart AudioBook Player | `a.a.smartplayer.app` | ? | Free version feature-limited + full-version unlock - historically a local gate. Uncovered 2026-10-06 |
| Cube ACR | `com.cubil.msgs.callrecorder` | ? | Popular call recorder; premium unlock is usually local. Uncovered 2026-10-06 |
| Today Weather | `com.todayweather.dev` | ? | Ads + premium upgrade. Uncovered 2026-10-06 |
| Alarm Clock Xtreme | `alarmclock.xtreme` | ? | Ads + pro; verify the package variant first. Uncovered 2026-10-06 |
| Radio Garden | `radio.garden` | ? | Fits the Simple Radio / Podcast Republic cluster. Uncovered 2026-10-06 |
| UEFA Champions League app | `com.uefa.ucl` | ? | Football-adjacent (Sofascore/FotMob/Livescore cluster); needs a triage for ad SDK vs sponsor content. Uncovered 2026-10-06 |

## Tier 3 - simple Pro unlocks

| App | Package | Latest seen | Notes |
|---|---|---|---|
| Device Info HW | `ru.andr7e.deviceinfohw` | 5.27.1 | **Blocked 2026-09-29** - no Pro boolean exists in this build; see blocklist |
| Xplore File Manager | `com.lonelycatgames.Xplore` | 4.49.10 | Pro features via its own license scheme - needs triage. Still uncovered 2026-10-06 |

## Now covered by community bundles (checked 2026-10-06 - do not duplicate)

MacroDroid (3 bundles), Pocket Casts, Hevy, CapCut, InShot, VN Video Editor, PicsArt,
Photomath, MX Player (`.ad`), AccuWeather, Alarmy, Strong, Lifesum, ReadEra, Automate,
Poweramp, Twitch (6 bundles), Stremio. Re-check before starting any app on this list.

## Not suggested (checked, rejected or deferred)

- myTuner Radio (`com.mytuner.mobile`), Forza Football (`com.forzafootball`), The Free
  Dictionary (`com.tfd.mobile.TfdSearch`) - not on APKMirror; try APKPure/Aptoide first.
- Squid (`com.steadfastinnovation.android.projectpapyrus`) - premium validated server-side.
- Root Explorer (`com.speedsoftware.rootexplorer`), Titanium Backup
  (`com.keramidas.TitaniumBackup`), Torque (`org.prowl.torquefree`), Symfonium
  (`com.symfonion.symfonium`) - paid/trial apps; works like Tasker but it is straight
  piracy. Only on explicit request.
- Online games on the target device (Jawaker Tarneeb, Racify, UEFA Eurofantasy, Sorare)
  - online-server games: training/economy state is server-authoritative (the same wall
  the UFM 27 mod hit; see `apks/games/UFM 27/MODDING-NOTES.md`). Skip the whole category.

## Recommended first batch

Shazam + Simple Radio + Device Info HW + Pi Music Player: two trivial ad kills and two
local pro gates, all verified available on APKMirror.

## Status (2026-09-19)

Tier 1 progress, all checked against the latest APKMirror builds:

| App | Status |
|---|---|
| Textra SMS | **Shipped** - "Enable Pro" (license pref class located structurally; l() -> true, k() -> false) |
| jetAudio | **Shipped** - "Enable Pro" (purchase info class located by its inline literal; leaf getters -> true) |
| Unified Remote | **Shipped** - "Enable Pro" (RevenueCat `EntitlementInfo.isActive()` -> true) |
| AZ Screen Recorder | **Blocked** - full PairIP (`SignatureCheck`, `VMRunner`, `VmDecryptor`); see patch-blocklist |
| ACR Phone | Parked - readable billing prefs bridge but the premium computation is obfuscated and a periodic purchase-refresh worker can overwrite cached state |
| Car Scanner ELM OBD2 | Parked - fully obfuscated app code, no RevenueCat; billing path not located yet |
| JuiceSSH | Parked - purchases stored in an ORMLite `purchase` table, premium gate in obfuscated code |
| Business Calendar 2 | Parked - obfuscated app code; premium check not located yet |

