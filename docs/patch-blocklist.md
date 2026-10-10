# Apps that could not be patched - attempt records

A record of apps we tried and why they failed. Keep entries short; link the blocker,
not the story.

> **2026-10-07 - owner policy change:** anti-tamper / app-shield bypass patches are no
> longer categorically excluded from this bundle. Shielded apps (PairIP and similar) can
> be revisited with that in mind; the entries below record the factual blockers.

## Server-side entitlement — a client patch cannot unlock

| App | Package | Why |
|---|---|---|
| Flashscore (premium) | `eu.livesport.FlashScore_com` | No Play Billing client at all. The app carries an `ENTITLEMENT_TOKEN` validated server-side (`/api/v2/android/validate_subscription_v2`); premium content comes from their API. The "Disable ads" patch is the only meaningful one and ships. |
| Carrot Weather | `com.grailr.carrotweather` | Subscription validated by `verifySubscriptionWithGoogle` before entitlements apply. |
| EasyExpense | `com.easyexpense` | React Native + Hermes bytecode; RevenueCat with trusted entitlements. Research-grade, not worth it. |
| TOD TV | `com.todtv.tod` | Irdeto OTT DRM + Widevine and IP-based region locking; React Native + Hermes. Content decrypts only when the server issues a license; nothing client-side to flip. |
| AnyDesk | `com.anydesk.anydeskandroid` | Removed 2026-10-03 (issue #23). The Java license wrappers can be forced (label shows Professional, banner hidden, address book/registration open), but session time limits are enforced by the native core and session broker servers - the close-reason dispatcher only displays what the session layer reports and no client-side timer exists to patch. Shipping a patch that looks like premium but keeps time limits only generates support load. |
| Brave VPN | `com.brave.browser` | Checked 2026-10-07 on 1.96.61. The WireGuard peer (`mapped-ipv4-address`, `server-public-key`) only comes back from Guardian (`connect-api.guardianapp.com`) after the Play purchase token passes `verify-purchase-token` and a `subscriber-credential` is issued; forcing the local `brave_vpn_subscription_purchase` flag only fakes the UI. |
| Brave Leo Premium | `com.brave.browser` | Checked 2026-10-07 on 1.96.61. Premium models and limits need a server-signed SKU credential (blind-signed time-limited credential from the `/v1/orders/receipt` order), sent as the `__Secure-sku#brave-leo-premium` cookie to `ai-chat-premium.bsg`; `brave.ai_chat.subscription_active_android` only drives the settings UI. |

## Nothing to patch

| App | Package | Why |
|---|---|---|
| ZArchiver | `ru.zdevs.zarchiver` | 1.0.10 has no ad SDK and no billing/pro path in the dex. |
| Retro Music | `code.name.monkey.retromusic` | FOSS: no ads, no paid tier. |
| Infinity for Reddit (free) | `ml.docilealligator.infinityforreddit` | Checked 2026-10-11 on 7.4.1 (split base). No PairIP and no ad SDK in the dex (no GMA, AppLovin, Unity, Vungle, Facebook or InMobi). The "Subscribe" classes are Reddit subreddit subscriptions. Only 9 Play Billing references and no readable Plus gate. Paid Infinity+ is the separate `.plus` listing, which another bundle covers. |
| Brave (Playlist, News, Wallet, Search, Talk) | `com.brave.browser` | Checked 2026-10-07 on 1.96.61: no paywall code in the app; Search and Talk premium are web-account features. Brave Origin is the only premium-style Brave patch; ads, telemetry and promo prompts ship as experimental patches. |
| Device Info HW | `ru.andr7e.deviceinfohw` | 5.27.1 free has no ad SDK (zero ad strings in the dex), no billing client and no pro gate: no license checker, no pro preference, no pro-package presence check. Pro is a separate paid listing (`ru.andr7e.deviceinfohw.pro`) with its own build; the free build's only "pro" surfaces are an upsell menu item (Play Store link) and a stubbed report button. |

## Parked — possible but needs an app-specific deep dive

| App | Package | Why parked |
|---|---|---|
| BlackPlayer Free | `com.kodarkooperativet.blackplayerfree` | Uses the old AdMob dynamite/WebView generation: the dex only has identifier/mediation stubs and ad-unit strings, no core GMA classes to hook. |
| Action Launcher | `com.actionlauncher.playstore` | Fully obfuscated; IAP state did not surface with method-name scans. |
| FX Explorer | `nextapp.fx` | Plus state is hidden behind its plugin registry. |
| Cronometer | `com.cronometer.android` | APKMirror keeps returning Cloudflare 403 for our IP; likely RevenueCat/server-side anyway. |
| RadarScope | `com.basevelocity.radarscope` | Paid app, not published on APKMirror — cannot obtain a base APK from our source. |
| Google Maps | `com.google.android.apps.maps` | Skipped 2026-10-08 after research on 26.39.06. UI cleanup is client-side and anchorable (home category chips via `"AssistiveShortcutsRowLayout"`, Contribute tab via `id/contribute_tab_strip_button`, settings defaults via the `GmmSettings` boolean getter; promoted pins come from a separate `ListPromotedPinAds` RPC). The blocker: every backend call sends the API key with the runtime signing-cert SHA-1 (`X-Android-Cert`) plus DroidGuard/PO-token attestation, and sign-in uses first-party OAuth scopes, so a re-signed build is expected to lose search/directions/data. Other bundles get around this by sending Google's own certificate, which this bundle won't do. Not runtime-tested. |
| CCleaner | `com.piriform.ccleaner` | Checked 2026-10-10 on 26.16.1. No PairIP. Ads come from ~15 SDKs (ironSource, AppLovin, Unity, GMA, Vungle and more). The Pro state lives in the obfuscated Avast Cleaner code (4,052 app methods, most renamed `o.?`) with Play Billing and Avast account strings, and no clear local getter has been found. Deep dive needed. |
| Sygic | `com.sygic.aura` | Checked 2026-10-10 on 26.6.1 (split base). No PairIP. The dex has one app class, so the navigation logic is native and the ad and premium checks are not in Java. Native deep dive needed. |
| iHeartRadio | `com.clearchannel.iheartradio.controller` | Checked 2026-10-10 on 10.67.0. No PairIP. Ships a native anti-tamper library (`libairshield_light_mbed_jni`). Premium is subscription-based. Likely blocked. |
| Flipboard | `flipboard.app` | Checked 2026-10-10 on 4.3.65 (split base). No PairIP. Ads arrive inside the server feed (`FeedItem.isNativeAd`, `isPromoted`, and similar), not behind one client gate. Needs feed filtering. |
| FC Pro 2 | `com.undergroundcreative.footballchairmanpro2` | Attempt failed (v1.2.2, 2026-09-19). Commercial app shield: game logic is encrypted web assets (`www/js/min-122.js`) decrypted by a native loader the shield extracts at runtime, strings are natively encrypted, a re-signed build dies in the native integrity check before any dex patch runs, and live updates can replace local code. Unpacking the shield is a research project. |
| YouCut | `com.camerasideas.trimmer` | Issue #44, 2026-10-09. The Pro gate IS client-side and forceable (`store/billing/d.d(Context)Z`, holds "SubscribePro"/"com.camerasideas.trimmer.vip" → `returnEarly(true)`), but any re-signed build self-kills: `libisvideoengine.so` `JNI_OnLoad` (@arm64 `0x633f8`) calls a gate (`0x6f744`) that runs `kill(getpid(), 9)` unless a native signing-cert verify (`0x70d1c`, uses `android/content/pm/Signature`, `CertificateFactory`) and an anti-debug check (`0x714f8`, `xposed.isDebug`/`VMDebug`) both pass. Fires at library load on any main-page action that loads the video engine (gear/new project/templates/AI) — SIGKILL, no Java exception, confirmed on the re-signed STOCK (unpatched) build too. Decoys: `check_package_name` returns 1 (dead code), and `com.cer.CerChecker`/`libcer` is unrelated (forcing its wrappers to 0 did nothing). Only fix is a native `.so` patch (NOP the single `bl kill@plt` @arm64 `0x6f79c` in both arm64-v8a and armeabi-v7a), which Morphe's bytecodePatch/resourcePatch model can't emit. Revisit if the engine gains native-lib patching. The prior shipped "Enable Pro" patch was bytecode-verified only, never runtime-tested, so it crashed from the start. |

## Blocked by PairIP VM and native tamper check (2026-10-10)

| App | Package | Why |
|---|---|---|
| Zedge | `net.zedge.android` | 9.40.1 (APKPure XAPK, full split set). PairIP with `LicenseClient`, `StartupLauncher` → `VMRunner`, `SignatureCheck.verifyIntegrity` (signing-cert hash check) and 67 encrypted VM assets. Public bypasses either cover only the license layer (`pairip-disabler` exits on `VMRunner`) or need the `libpairipcore` tamper check and the zip-CRC step, which a bytecode patch can't emit. Same blocker as AZ Screen Recorder. |
| Xplore File Manager | `com.lonelycatgames.Xplore` | 4.49.10. PairIP `VMRunner` + `libpairipcore.so` + 183 encrypted assets. App and Pro-gate names are obfuscated and the gate was not located. Same blocker as Zedge. |
| Lose It! | `com.fitnow.loseit` | 18.5.401 (APKPure, split base). Full PairIP stack: `LicenseClient`, `StartupLauncher` → `VMRunner`, `SignatureCheck`, `VmDecryptor`, 1,069 encrypted assets. RevenueCat entitlements on top. Same blocker as Zedge. |

## Removed at the owner's request

| App | Package | Why |
|---|---|---|
| Saphe Link | `my.saphelink` | Removed 2026-10-07 at the owner's request. Last verified 6.6.0. |

## Known fingerprint drift (re-anchor later, app stays supported)

| App | Last good | Failing on |
|---|---|---|
| LibrePods | 1.0.0-rc1-play-63 | no store mirror carries the pinned Play build; GitHub ships FOSS builds only (still true for v1.0.1-rc1, 2026-10-07) |

Re-anchored and bytecode-verified on 2026-09-18 (removed from this table):
- FotMob 237.17536.20260911 (storage-agnostic getter search)
- Brave Origin 1.95.104 (the subscription writer swapped its parameter order)
- MyFitnessPal 26.37.0 (premium moved to queryenvoy enum parsers)
- BoxBox 5.4.9 (telemetry and interstitial overloads now patched by class scan)

Re-anchored and bytecode-verified on 2026-10-07:
- Brave Origin 1.96.61 (two reworded log anchors now matched by prefix)
- Bluecoins 13.1.149 (fully R8-obfuscated, BillingDomainManager and kotlinx flow names gone; premium flow now forced at the "Startup: Encryption: Premium is" emit and the Google Play version-override combine)
- BoxBox 5.4.16 (dead launchBillingFlow step removed; Firebase Analytics kill re-anchored on the measurement logEvent(String,String,Bundle,Z,Z,J) shape — both steps had silently matched nothing since 5.4.9)

## Package-name traps (avoid downloading the wrong app)

- Flashscore is `eu.livesport.FlashScore_com`, **not** `com.flashscore`.
- OneFootball is `de.motain.iliga`, **not** `com.onefootball`.
- SD Maid SE is `eu.darken.sdmse`, **not** `eu.thedarken.sdm.se`.
- The FotMob Wear OS build shares `com.mobilefootie.wc2010`; its versions end in `w`
  (e.g. `236.253021660w.20260827`). Check `android.hardware.type.watch` on any download.
- WiFi Analyzer: APKMirror's "3.11.1-L" is a different package, `com.farproc.wifi.analyzer.classic`.
  The supported `com.farproc.wifi.analyzer` line's newer 3.11.x builds have a lower versionCode
  (138) than 3.10.5-L (999), so 3.10.5-L is still the current build of the supported package.

## Patch limitations worth remembering

- **FairEmail + Gmail OAuth**: tokens come from `AccountManager`, and Google only issues them
  to packages signed with the OAuth client's registered certificates. A re-signed patched build
  cannot get them — use an app password for Gmail accounts. The pro patch itself works.
- **Flashscore premium**: server-side entitlement (see above).
