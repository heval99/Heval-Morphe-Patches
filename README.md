# 👋🧩 heval patches

Custom Morphe patches by heval99.

## ❓ About

Patches for apps I like. Covers ads, telemetry, and premium unlocks for 13 apps (AdGuard,
BoxBox, CapCut, Clipboard, FishBuddy, Football Chairman Pro 2, FotMob, IPTV Player - Opus,
MyFitnessPal, OttPlay, Smart Launcher, Sofascore, and 90 Day Challenge), plus a handful of
universal patches that target common ad/telemetry/root-detection SDKs regardless of app.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0](https://github.com/heval99/morphe-patches/releases/tag/v1.0.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;15 patches total
<details open>
<summary>📦 Sofascore&nbsp;&nbsp;•&nbsp;&nbsp;6 patches</summary>
<br>

**🎯 Supported versions:**

| 26.07.27 | 26.08.03 |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Block marketing notifications](#block-marketing-notifications) | Blocks promotional and marketing notification prompts and modals. |  |
| [Disable Facebook SDK](#disable-facebook-sdk) | Disables Facebook SDK initialization, marketing, and ad network activity. |  |
| [Disable Play Integrity](#disable-play-integrity) | Bypasses Google Play Integrity API checks. |  |
| [Disable ads](#disable-ads) | Disables banner, interstitial, feed, native, preroll and rewarded ads. |  |
| [Disable telemetry](#disable-telemetry) | Disables AppsFlyer, Firebase Analytics, Crashlytics, and Adjust telemetry. |  |
| [Enable Premium](#enable-premium) | Unlocks AI insights and premium features locked behind subscription. |  |

</details>

<details open>
<summary>📦 Brave Browser&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.93.136 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Brave Origin](#brave-origin) | Unlocks Brave Origin and enables feature toggle controls. |  |

</details>

<details open>
<summary>📦 BoxBox&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Disable ads](#disable-ads) | Prevents AppLovin interstitial ads from loading and showing. |  |
| [Disable telemetry](#disable-telemetry) | Disables AppsFlyer, Firebase Analytics and Crashlytics event logging. |  |
| [Enable Premium](#enable-premium) | Unlocks premium features by bypassing RevenueCat subscription checks. |  |

</details>

<details open>
<summary>📦 Saphe Link&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 6.5.5 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Disable telemetry](#disable-telemetry) | Disables Braze custom event tracking. Firebase Analytics/Crashlytics are covered by the universal "Disable Firebase Analytics & Crashlytics" patch. |  |
| [Enable Premium](#enable-premium) | Unlocks all features locked behind the Saphe subscription paywall (navigation, car integration, speed limits, voice alarms, roadwork detection, animal nearby, slow-moving traffic, emergency vehicle, etc.). |  |

</details>

<details open>
<summary>📦 FotMob&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Enable FotMob+](#enable-fotmob) | Enables app features locked behind the subscription paywall. |  |

</details>

<details open>
<summary>📦 AnyDesk&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 8.3.4 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Enable Premium](#enable-premium) | Unlocks premium features by patching the Java license wrapper methods in JniAdExt: isFreeLicense returns false (app treats the license as paid), account registration and address book are allowed, and the remove-license option is available in settings. Note: the underlying license validation is native (libanydesk.so) and cannot be patched via bytecode — this patch only affects the Java-layer feature gates. |  |

</details>

<details open>
<summary>📦 MyFitnessPal&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Enable Premium+](#enable-premium) | Enables app features locked behind the subscription paywall. |  |

</details>

<!-- PATCHES_END -->

#### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=heval99/morphe-patches

Or manually add this repository url as a patch source in Morphe: https://github.com/heval99/morphe-patches

### 🛠️ Building

To build heval patches,
you can follow the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation).

## 📜 License

heval patches are licensed under the [GNU General Public License v3.0](LICENSE)
