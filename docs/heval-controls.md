# Heval Controls

Heval Controls is an in-app settings screen for patched apps. The user can switch supported
patch features on or off at runtime without patching again. A choice is a saved preference that
the patched code reads, so a change takes effect at once, or after a restart for features read
only at startup.

Status (2026-11-03): Truecaller 26.42.5 ships it. Its "Heval Controls" patch (default on) adds a
"Heval Controls" row below Help on the main Settings screen, and 20 converted Truecaller patches
are runtime switches (`truecaller/controls/TruecallerControls.kt` and the patches that use
`truecallerControls.featurePatch`; list in [Truecaller features](#truecaller-features)). LinkedIn
4.1.1261 was the first app (an in-app entry row in
the home nav panel and runtime features). It was removed on 2026-10-15 after the test account was
banned, so the LinkedIn names below are examples only. The design study, with alternatives and the
HushMessenger analysis, is kept outside the repo; this page is the developer guide.

## Contents

- [Architecture](#architecture)
- [How a feature answers at runtime](#how-a-feature-answers-at-runtime)
- [Adding Heval Controls to an app](#adding-heval-controls-to-an-app)
- [Feature hook example](#feature-hook-example)
- [Truecaller features](#truecaller-features)
- [App-specific Java helpers](#app-specific-java-helpers)
- [What cannot be toggled at runtime](#what-cannot-be-toggled-at-runtime)
- [Crash guard and safe mode](#crash-guard-and-safe-mode)
- [Settings screen](#settings-screen)
- [Registry format](#registry-format)
- [Testing](#testing)
- [Known limits and next steps](#known-limits-and-next-steps)
- [Credits](#credits)

## Architecture

| Part | Path | Role |
|---|---|---|
| Extension | `extensions/hevalcontrols/` | Builds `extensions/hevalcontrols.mpe`: the init provider and the settings screen, plus the bundled core. |
| Core library | `extensions/hevalcontrols/core/` | Plain Android library (not an extension, because it is nested; same rule as `extensions/smartwod/stub`). Holds the engine, the registry codec and the crash-guard logic, with JVM unit tests. |
| Patch helpers | `patches/src/main/kotlin/app/template/patches/shared/hevalcontrols/` | `hevalControlsPatch(...)` (the host patch), `featurePatch(...)`, `HevalFeature`, the registry encoder and the gate helpers. |

Java classes (package `app.template.extension.hevalcontrols`):

| Class | Purpose |
|---|---|
| `HevalSettings` (core) | The engine. `isEnabled(String)` is the hot path that every hook calls. |
| `Registry`, `RegistryCodec`, `Feature` (core) | Hold the feature list that the patch writes at patch time, and decode it. |
| `CrashGuard` (core) | Counts quick crashes at startup and turns on safe mode. |
| `PrefsStore`, `Store`, `AppContext` (core) | Store choices in SharedPreferences, and provide the context and process helpers. |
| `InitProvider` | A non-exported ContentProvider that starts the engine and the crash guard before `Application.onCreate`. |
| `ui.HevalControlsActivity`, `ui.Views`, `ui.Palette`, `ui.Diagnostics` | The settings screen, with every view built in code. |

The extension is pure Java (minSdk 23, no androidx, no kotlin-stdlib, no resources). An `.mpe`
is a single dex file. Its resources and manifest are discarded, so every string and colour lives
in code, and the patch adds the manifest entries.

There is only one copy of the core in the app. Only the host patch calls
`extendWith("extensions/hevalcontrols.mpe")`. An app-specific extension must depend on
`:extensions:hevalcontrols:core` with `compileOnly`, never `implementation`. The patcher merges
duplicate classes with "first copy wins", so a second copy built from a different revision would
be silently ignored.

## How a feature answers at runtime

`HevalSettings.isEnabled("<id>")` returns `true` only when the patched behaviour should run.
`false` always means stock behaviour. The rules, in order:

1. **Before the engine has started** (no context yet, for example inside
   `Application.attachBaseContext`): it returns the feature's registered default, and does not
   cache that answer. The engine starts in `InitProvider.onCreate`. If a hook runs first, the
   engine starts lazily through `ActivityThread.currentApplication()`.
2. **Engine failure** (the settings could not be read), **global pause** or **safe mode**: it
   returns `false` for every feature.
3. **Unknown id**, an unsupported registry, or a **dependency** that is off, missing or cyclic:
   it returns `false`.
4. Otherwise it returns the user's saved choice, or the registered default.
5. **Any exception**: it returns `false`.

"Fail-open" here means the app keeps working and falls back to stock behaviour. It never means
that a modification is forced on.

The hot path does no file IO and no allocation. It reads one `volatile` reference to an
immutable snapshot, then does one `HashMap` lookup. The snapshot is rebuilt on every change by a
SharedPreferences listener. That listener is held strongly, because SharedPreferences keeps its
listeners in a `WeakHashMap`.

Storage is the private SharedPreferences file `heval_controls`, with these keys:

| Key | Holds |
|---|---|
| `f.<id>` | A feature choice (boolean). |
| `_paused` | The global pause. |
| `_safe_mode` | Safe mode. |
| `_theme` | The theme: `system`, `light`, `dark` or `amoled`. |

Writes use `commit()`, so a restart or a crash cannot lose them. Crash-guard records are separate
`AtomicFile`s in `filesDir/heval_controls/`.

## Adding Heval Controls to an app

1. **Create the host**, once per app, in `patches/.../<app>/controls/<App>Controls.kt`. The
   example uses LinkedIn names (the real file also passes its entry-point patch, see step 3).

   ```kotlin
   package app.template.patches.linkedin.controls

   import app.template.patches.shared.Constants.COMPATIBILITY_LINKEDIN
   import app.template.patches.shared.hevalcontrols.hevalControlsPatch

   internal val linkedInControls = hevalControlsPatch(COMPATIBILITY_LINKEDIN)

   // Expose the host patch, so Manager lists "Heval Controls".
   @Suppress("unused")
   val linkedInHevalControlsPatch = linkedInControls.patch
   ```

   The host patch adds the provider and the activity to the manifest, merges the `.mpe`, and in
   `finalize` writes the registry, the bundle version and the patched app version into
   `Registry`. It is available on every installer, Root Mount included (see
   [Known limits](#known-limits-and-next-steps) for what a mount install loses).

2. **Write each feature as its own named patch** with `featurePatch`. That patch depends on the
   host, runs your edits, and registers the feature only after every edit has succeeded:

   ```kotlin
   @Suppress("unused")
   val hidePromotedPostsPatch = linkedInControls.featurePatch(
       HevalFeature(
           id = "linkedin.hide_promoted",
           name = "Hide promoted posts",
           description = "Removes promoted posts from the feed.",
           category = "Feed",
           default = true,              // keep today's behaviour for converted patches
       ),
   ) {
       // Validate every hook site first, then edit.
       val method = PromotedVisibleFingerprint.method
       method.hevalReturnEarly("linkedin.hide_promoted", false)
   }
   ```

   Patches that cannot be switched at runtime can still be listed. Register them with
   `buildTimeOnly = true`, and they show as "Applied at patch time".

   A feature patch `dependsOn` the host, so selecting any converted patch also applies "Heval
   Controls". The host is selected by default; deselecting it in Manager while a converted patch
   is selected still applies it. It also means a broken host anchor (for Truecaller, the Settings
   entry point) makes every converted patch fail loudly, not only the host.

3. **Add an entry point.** This is per app. A menu-row hook calls
   `HevalControlsActivity.open(Context)` through
   `invoke-static {pX}, Lapp/template/extension/hevalcontrols/ui/HevalControlsActivity;->open(Landroid/content/Context;)V`
   (or starts the activity by class name). The activity is `exported="false"`, so only the app
   itself can open it. Pass the unnamed entry-point patch to the host,
   `hevalControlsPatch(COMPATIBILITY_X, dependencies = setOf(entryPatch))`, so the entry is always
   part of "Heval Controls". Truecaller's entry (`truecaller/controls/TruecallerControls.kt` and
   `HevalControlsEntry` in `extensions/truecaller`) adds a row styled like Help right after it on
   the main Settings screen. An entry must first check that the activity is registered
   (`PackageManager.getActivityInfo` for the component; `NameNotFoundException` means a Root Mount
   install with the stock manifest) and show nothing otherwise. If starting the activity fails, it
   logs and shows a short toast.

4. **Smoke test.** Assert that the extension classes are in the emitted dex, that the
   `Registry.encoded()` string equals `HevalRegistryCodec.encode(...)` of the expected features,
   and that each gated method starts with the gate. Also assert the provider (authority
   `<package>.hevalcontrols`, `exported=false`, `initOrder=1000`) and the activity
   (`exported=false`) in the decoded manifest. `HevalControlsPatcherTest.kt` shows all of these
   assertions.

Feature ids must match `^[a-z][a-z0-9_.]{0,63}$`. Use the form `<app>.<feature>`. Never rename a
shipped id: it names the user's saved choice.

## Feature hook example

`addHevalGate` inserts the gate: while the feature is on, the patched path runs, and the original
code after it is the stock path. `addHevalStockPath` is the inverse, for code the patch generated
itself (a helper method that exists only in the patched app): while the feature is off, the given
stock path runs and must end with a return, throw or goto; while it is on, execution falls into the
original (patched) code. Truecaller's Hide Blocking tab, Show caller number in call log and Fix T9
contact search use it at index 0 of their own helpers. The `hevalReturnEarly` and
`hevalReturnNull` helpers wrap `addHevalGate` for the three common shapes:

| Helper | Method shape | Patched path |
|---|---|---|
| `hevalReturnEarly(id)` | `void` | Returns at once (`return-void`). |
| `hevalReturnEarly(id, value)` | `boolean` | Returns `value`. |
| `hevalReturnNull(id)` | Returns an object | Returns `null`. |

For example, `isPromotedVisible()Z` with `hevalReturnEarly("linkedin.hide_promoted", false)`
becomes:

```smali
.method public final isPromotedVisible()Z
    .registers 3
    const-string v0, "linkedin.hide_promoted"
    invoke-static/range {v0 .. v0}, Lapp/template/extension/hevalcontrols/HevalSettings;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :heval_stock
    const/4 v0, 0x0
    return v0
    :heval_stock
    # original body, untouched
    iget-boolean v0, p0, Lcom/linkedin/...;->a:Z
    return v0
.end method
```

How the scratch register is chosen:

- At index 0 the gate uses `v0`, because nothing is live at method entry. The method must have at
  least one local register.
- At another index, pass `register =`, or the helper uses morphe-patches-library's
  `findFreeRegister`.
- The helper refuses parameter registers, and it refuses to insert before a `move-result`.
- The invoke is `/range`, so registers above v15 work.

Any other edit can use `addHevalGate` with your own patched path:

```kotlin
method.addHevalGate("app.feature", index = 0) { v -> "return-void" }

// A generated helper whose body is the patched behaviour: while off, return the stock answer.
helper.addHevalStockPath("app.feature") { v -> "return-void" }
```

Two more shapes keep a stock path where the original patch removed or overwrote code:

- `addHevalSkip(id, index, target)` jumps from `index` to `target` while the feature is on
  (`if-nez` on the isEnabled result) and falls through to the original code while it is off. Use it
  instead of removing an instruction (Disable call button haptics keeps each haptic dispatch and
  jumps over it) or instead of an unconditional `goto` (the LazyColumn item skips of Hide frequently
  called contacts and Hide important senders). The scratch register comes from `findFreeRegister`,
  so pick an `index` whose own code already reaches `target`.
- `MutableClass.addHevalValueHelper(id, name, type) { v -> ... }` adds a static `(T)T` helper (`Z`,
  `I` or `F`) that returns its argument unchanged while the feature is off, and
  `MutableMethod.filterThroughHevalHelper(index, register, helper)` passes a register through it
  (`invoke-static/range`, `move-result`). Use it where the patch forced or changed a value in place
  (`const/4 vX, 0x0` before a branch or a store, `neg-float`): the site needs no free register.

```smali
# Restore classic call log: the "featureFavouriteContacts" result before the swipe guard
invoke-virtual {v1}, Lgj9;->a()Z
move-result v1
invoke-static/range {v1 .. v1}, Lz78;->morpheClassicCallLogSwipe(Z)Z   # false while on, v1 while off
move-result v1
if-nez v1, :cond_43
```

The usual rules still apply (AGENTS.md):

- Gate only concrete methods (`implementation != null`).
- Use `.method`, not `methodOrNull`, for mandatory anchors.
- Gate every overload that matters.
- Use stable anchors.

## Truecaller features

Truecaller 26.42.5 (2026-11-03). Every switch defaults on; its Manager patch keeps its name,
description and default. A shipped id is never renamed.

| Feature id | Patch | Restart | Gated sites |
|---|---|---|---|
| `truecaller.hide_promotions` | Hide promotions | yes | toggle overrides |
| `truecaller.disable_nags` | Disable nags | no | toggle overrides |
| `truecaller.disable_auto_launch` | Disable auto-launch | no | toggle override |
| `truecaller.disable_update_prompts` | Disable update prompts | no | gates at index 0 |
| `truecaller.hide_assistant_tab` | Hide Assistant tab | yes | gate at index 0 (answers false) |
| `truecaller.hide_scam_feed` | Hide Scam feed | yes | gate at index 0 (answers false) |
| `truecaller.hide_blocking_tab` | Hide Blocking tab | yes | its tabs helper (stock path) |
| `truecaller.hide_family_button` | Hide family button | yes | toggle override |
| `truecaller.show_caller_number` | Show caller number in call log | no | its subtitle helper (stock path) |
| `truecaller.fix_t9_search` | Fix T9 contact search | yes | its refresh helper (stock path) |
| `truecaller.hide_after_call_contacts` | Hide after-call screen for contacts | no | toggle overrides |
| `truecaller.hide_business_media` | Hide business media | no | toggle overrides, screen entry gate, media helper |
| `truecaller.hide_messages_tab` | Hide Messages tab | yes | its tabs helper, default tab getter, selectTab, bar helper |
| `truecaller.hide_premium_tab` | Hide Premium tab | yes | its tabs helper, Settings `isPremiumItemVisible` store (value helper) |
| `truecaller.restore_classic_call_log` | Restore classic call log | yes | toggle override, swipe guard (value helper) |
| `truecaller.hide_call_log_filters` | Hide call log filters | yes | filter row composable and filter setter (index 0) |
| `truecaller.hide_frequent_contacts` | Hide frequently called contacts | no | LazyColumn item skip |
| `truecaller.hide_important_senders` | Hide important senders | no | LazyColumn item skip |
| `truecaller.disable_call_button_haptics` | Disable call button haptics | no | haptic dispatch skips, drag progress (value helper) |
| `truecaller.swap_call_buttons` | Swap call answer and decline buttons | no | offsets and call style check (value helpers), notification layouts |

Hide call log filters is marked restart because its row is a composable: an open call log screen
keeps the row it composed until the screen composes it again. Swap call answer and decline buttons
also has a resource part. It cannot be switched in place, so the patch adds swapped copies of the
notification layouts (`<name>_heval_swapped`) and leaves the stock ones untouched. The notification
class asks `SwapCallButtons.layout(int)` in `extensions/truecaller` for each layout id: the copy's
id (found by name at runtime, because the patcher assigns new resource ids when it encodes) while
the feature is on, the stock id while it is off.

Not converted: Disable ads, Disable telemetry, Disable contact upload, Enable Premium, GmsCore
support, Spoof signature, Hide Wear OS settings and Use SMS for verification codes.

## App-specific Java helpers

When a feature needs logic (list filtering, URL rewriting, reflection), put it in a per-app
extension. Read switches through `HevalSettings` with `compileOnly` access to the core. (The
LinkedIn extension so far only needs `compileOnly(project(":extensions:linkedin:stub"))` for
LinkedIn's own classes.) For example:

```kotlin
// extensions/<app>/build.gradle.kts
dependencies { compileOnly(project(":extensions:hevalcontrols:core")) }
```

```java
public static List<Object> filterFeed(List<Object> items) {
    if (!HevalSettings.isEnabled("linkedin.hide_promoted")) return items;   // stock
    try {
        // ... filter ...
    } catch (Throwable failure) {
        HevalSettings.reportFailure("linkedin.hide_promoted", failure);    // class + frame only
        return items;                                                       // stock on error
    }
}
```

`reportFailure` keeps only the exception class and the first `app.template.extension` frame,
never the message. The screen then shows the feature as "Failed, using stock behaviour" for the
rest of the process.

## What cannot be toggled at runtime

A runtime switch needs a code path where both the stock and the patched behaviour still exist.
These cannot have one:

- **Manifest edits.** Removed or added permissions, `<meta-data>` (Firebase or analytics
  deactivation), `<queries>`, package renames, removed providers or receivers. The app's own
  components could in principle be enabled or disabled with
  `PackageManager.setComponentEnabledSetting`, which applies after a restart. Removed permissions
  and meta-data cannot.
- **Resource edits.** XML defaults, layouts, strings, drawables.
- **Signature and identity.** "Spoof signature", "GmsCore support" (string rewrites across the
  dex), package names.
- **Structural bytecode changes.** Superclass swaps, deleted classes or methods, a wholesale
  `returnEarly` on methods without a stock path, or rewrites of hundreds of call sites, where
  each gate costs a call.
- **Process-start decisions.** SDK initialisation in `Application.onCreate`, dependency
  injection graphs, caches. Register these with `restartRequired = true`. The screen then shows
  a "Restart" badge and a "Restart now" bar.
- **Native libraries** and anything decided server-side.

Register these with `buildTimeOnly = true` if they should appear on the screen.

## Crash guard and safe mode

The crash guard is based on HushMessenger's `CrashGuard`:

- **Start record.** Every main-process start writes a record, `"<pid> <wall ms>"`.
- **Quick crash.** At the next start, the previous run counts as a quick crash in two cases:
  - On Android 11+: the exit history (`getHistoricalProcessExitReasons`) reports a Java crash,
    a native crash or an ANR within 60 s of the start.
  - On Android 10 and older: our uncaught-exception handler appended ` crashed` within 60 s.
- **Safe mode.** After three quick crashes in a row, safe mode turns on (`_safe_mode`, written
  with `commit()`), and every runtime feature answers stock. The process shows a toast.
- **Reset.** A process that stays alive for 60 s resets the streak.

The screen shows a safe-mode banner with two buttons:

- **Resume** turns safe mode off.
- **Resume paused** turns the global pause on first, so the user can switch features back on one
  at a time.

The limits are stated in the UI, too. Safe mode cannot help when:

- the crash comes from a patch-time change;
- the crash happens before `InitProvider` runs (for example in `attachBaseContext`);
- it is a native crash on Android 10 or older;
- the app was installed with Root Mount: `InitProvider` is not registered there, so the crash
  guard is never armed and no start records are written. A safe mode saved earlier is still
  honoured by the lazily started engine.

## Settings screen

`HevalControlsActivity` is a framework `Activity` with views built in code.

**Main screen, from top to bottom:**

1. **Header**, then an information card: the app version, "patched for", the bundle version,
   the feature counts and the runtime status.
2. **Banners**: safe mode, engine failure, unreadable registry, and pending restarts with a
   "Restart now" button.
3. **Master switch**: "Pause all Heval modifications".
4. **Feature rows**, one section per category. Each row has a name, a description, a state line
   and, for restart-required features, a "Restart" badge.
5. **Appearance**: a theme cycle (System, Light, Dark, AMOLED black).
6. **Diagnostics**: "Copy diagnostics".
7. **App**: "Restart app", and "Reset all to defaults" with an inline confirmation.

**Row states:** On, Off, Off (paused / safe mode / needs X), Restart required, Unsupported (a
dependency that is not in this APK), Failed, and Applied at patch time.

**Accessibility:**

- Each row is one focus stop, announced as a switch with its state (`setStateDescription` on
  API 30+).
- Headings are marked on API 28+.
- Touch targets are at least 48 dp.
- Changes are announced.
- The root ignores touches while it is obscured (tapjacking).

**Layout and system UI:**

- Edge-to-edge insets include the display cutout.
- The light or dark system bar icons follow the palette.
- Back closes the reset confirmation through `OnBackInvokedCallback` on API 33+, with
  `onBackPressed` below that.
- The layout direction follows the locale. The host's `supportsRtl` still decides whether RTL
  applies.

**Diagnostics** contain versions, the device model, flags and feature states. Failures appear as
an exception class and frame only. The report has no account data, app content or identifiers.
The clipboard copy is marked sensitive.

**Extension points** (not built yet): search above the categories, import and export next to
"Copy diagnostics", and profiles after the master pause.

## Registry format

The host patch writes the registry string into `Registry.encoded()` with a `const-string/jumbo`
instruction. It does not use `returnEarly(String)`, because that emits unescaped smali and breaks
on tabs and line feeds.

Version 1 looks like this:

```
hevalcontrols-registry:1
<id> TAB <name> TAB <description> TAB <category> TAB <default 0|1> TAB <restart 0|1> TAB <buildTimeOnly 0|1> TAB <dep,dep>
```

Rules:

- Text fields escape `\`, tab, LF and CR as `\\`, `\t`, `\n` and `\r`.
- The decoder skips malformed or duplicate records, drops invalid dependency ids, ignores
  trailing fields (so appending a field stays version 1), and never throws.
- A different header version gives an empty, unsupported registry. Every hook then answers
  stock, and the screen asks for a repatch.

The fixture `extensions/hevalcontrols/core/src/test/resources/registry-v1.txt` pins the format on
both sides. The Kotlin encoder test and the Java decoder test both read it.

## Testing

| What | Command | What it covers |
|---|---|---|
| Core JVM unit tests | `.\gradlew :extensions:hevalcontrols:core:testDebugUnitTest` | Registry decode and versioning, defaults, toggle and persistence on a fake store, pause, safe mode, fail-open, dependencies, restart-pending, unknown ids, crash-guard counting and resume. |
| Patch-side tests | `.\gradlew :patches:buildAndroid :patches:cleanTest :patches:test --tests "HevalControls*" --no-build-cache` | `HevalControlsTest` covers the encoder against the fixture, feature validation, the manifest components, the gate helpers and that the Truecaller host is available on every installer (Root Mount included). `HevalControlsPatcherTest` runs a test-only host and feature through the real patcher on `apks/nativecamera/base.apk` (skipped without it). |
| Lint | `.\gradlew :extensions:hevalcontrols:core:lintRelease :extensions:hevalcontrols:lintRelease` | Checks API levels against minSdk 23. |

CI (`release.yml`) runs no tests. Consider adding the core unit-test task there.

## Known limits and next steps

- **Other processes.** A secondary process starts the engine lazily and reads the choices once.
  The change listener does not reach across processes, so a change applies there after a
  restart. The crash guard runs in the main process only.
- **Root Mount** keeps the stock manifest, so `InitProvider` and `HevalControlsActivity` are not
  registered (the patch still adds them; mount ignores them). The patched dex runs, so the
  features work: the first hook starts the engine lazily through
  `ActivityThread.currentApplication()`, answering the registered defaults until the Application
  exists and the saved choices after that. What is lost: the settings screen (the entry row is
  not shown, so every feature stays at its default), the crash guard and the safe-mode toast.
  Because the feature patches depend on the host, the host stays available on Root Mount;
  making it unavailable would remove every converted patch there.
- **Restart-required features are informational.** `isEnabled` returns the current choice. The
  screen counts differences from the process start.
- **Hook failures** are kept in memory only, for the current process.
- **Not built yet:** a launcher shortcut (LinkedIn replaces its own dynamic shortcuts, so one
  added by us would be removed), search, import and export, profiles and translations.
- **RTL:** in an RTL layout, LTR values such as "4.1.1261 (216900)" in the information card are
  reordered by bidi ("(216900) 4.1.1261"); they should be forced LTR.

## Credits

The engine rules, init provider, crash guard, view helpers, settings screen structure and patch
gate shape are based on [SysAdminDoc/HushMessenger](https://github.com/SysAdminDoc/HushMessenger)
(GPL-3.0) at `5ae3ddf`. They were modified on 2026-10-14. Each ported or adapted file carries a
header that says what was taken and what changed, and the repository `NOTICE` has the entry.
