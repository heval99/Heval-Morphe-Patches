/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android: replaces ChromeUserscriptController, MonkeyRuntime and
 * NativeInstallAction with hook-driven entry points (no polling).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public static API called from the Brave bytecode hooks. Every method catches Throwable.
 * <ul>
 *   <li>{@link #install}: first thing in {@code SplitChromeApplication.onCreate()}, every process;
 *       acts only in the main browser process.</li>
 *   <li>{@link #onPageLoad}: after a primary-frame, non-same-document navigation finished.</li>
 *   <li>{@link #openManager}: from the app-menu hook.</li>
 *   <li>{@link #installFromUrl}: install review for an https userscript or Fork page URL.</li>
 * </ul>
 * There is no URL poller: scripts are injected once per finished navigation (a reload is a new
 * document and is injected again; same-document navigations are not reported to us).
 */
@SuppressWarnings("unused")
public final class BraveMonkey {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final long PROMPT_DEDUPE_MS = 3000;
    /** At most one install review launch from a page per this long, whatever the URL. */
    private static final long PROMPT_MIN_GAP_MS = 30_000;

    private static volatile Context appContext;
    private static Handler main;
    private static String lastPromptUrl;
    private static long lastPromptAt;
    private static long lastAnyPromptAt = Long.MIN_VALUE / 2;
    /** The activity that is resumed right now (null while the app is in the background). */
    private static volatile WeakReference<Activity> resumed;

    private BraveMonkey() { }

    public static void install(Application app) {
        try {
            if (app == null || !INSTALLED.compareAndSet(false, true)) return;
            if (!isMainProcess(app)) return;
            appContext = app;
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    resumed = new WeakReference<>(activity);
                    try {
                        if (!STARTED.compareAndSet(false, true)) return;
                        UserscriptShortcut.publish(app);
                        MonkeyStore.warmUp(app);
                    } catch (Throwable error) {
                        Logger.e("start failed", error);
                    }
                }
                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityPaused(Activity activity) {
                    WeakReference<Activity> current = resumed;
                    if (current != null && current.get() == activity) resumed = null;
                }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) { }
            });
            Logger.i("installed");
        } catch (Throwable error) {
            Logger.e("install failed", error);
        }
    }

    public static void onPageLoad(Object tab) {
        try {
            if (tab == null || appContext == null) return;
            // Never run the injection inside Brave's native-to-Java load callback (it would call
            // Tab.loadUrl before the tab's own observers ran): always post it. When called on the
            // main thread the URL is read now, and the post is dropped if the tab moved on.
            final String seen = Looper.myLooper() == Looper.getMainLooper() ? BraveBridge.urlOf(tab) : null;
            mainHandler().post(() -> {
                try {
                    if (seen != null && !seen.equals(BraveBridge.urlOf(tab))) return;
                    handlePageLoad(tab);
                } catch (Throwable error) {
                    Logger.e("page load failed", error);
                }
            });
        } catch (Throwable error) {
            Logger.e("onPageLoad failed", error);
        }
    }

    /** String id of the menu row title, looked up by name (the patcher cannot name ids it adds). */
    public static int titleId(Context context) {
        try {
            int id = context.getResources().getIdentifier("bravemonkey_userscripts", "string", context.getPackageName());
            if (id != 0) return id;
        } catch (Throwable error) {
            Logger.e("titleId failed", error);
        }
        return android.R.string.untitled;
    }

    public static void openManager(Activity activity) {
        try {
            Context context = activity != null ? activity : appContext;
            if (context == null) return;
            Intent intent = new Intent(context, UserscriptManagerActivity.class);
            if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            Logger.e("openManager failed", error);
        }
    }

    public static void installFromUrl(Activity activity, String url) {
        try {
            String target = url == null ? "" : url.trim();
            if (!ViolentmonkeyCompat.isAllowedInstallRequest(target)) {
                Logger.w("install request refused: " + Logger.redact(target));
                if (activity != null) {
                    MonkeyUi.toast(activity, "Only https userscript links (.user.js / .user.css) or Greasy Fork / Sleazy Fork script pages");
                }
                return;
            }
            if (activity != null) {
                ForkSiteSupport.openInstallPreview(activity, target);
                return;
            }
            Context context = appContext;
            if (context == null) return;
            context.startActivity(new Intent(context, UserscriptInstallActivity.class)
                    .putExtra(ForkSiteSupport.EXTRA_URL, target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            Logger.e("installFromUrl failed", error);
        }
    }

    // ---------------------------------------------------------------- page load (main thread)

    private static void handlePageLoad(Object tab) {
        Context context = appContext;
        if (context == null) return;
        if (BraveBridge.isIncognito(tab)) return;
        String url = BraveBridge.urlOf(tab);
        if (url == null || !UrlPatternMatcher.isInjectableScheme(url)) return;

        // Install helpers: they only ever open the review screen, which needs a tap on Install.
        if (url.startsWith("https://")) {
            if (ForkSiteSupport.isForkPage(url)) {
                BraveBridge.run(tab, ScriptInjector.installClickBridge(context.getPackageName()));
            }
            if (ViolentmonkeyCompat.isDirectUserscriptUrl(url)) promptDirectInstall(context, url);
        }

        if (!MonkeyStore.globalEnabled(context) || MonkeyStore.hostDisabled(context, url)) return;
        if (MonkeyStore.isLoaded()) {
            inject(tab, url, MonkeyStore.matching(context, url));
            return;
        }
        // First page load before the warm-up finished: match off the main thread, then come back
        // and inject only if the tab still shows the same page.
        MonkeyStore.runInBackground(() -> {
            try {
                List<Userscript> scripts = MonkeyStore.matching(context, url);
                if (scripts.isEmpty()) return;
                mainHandler().post(() -> {
                    try {
                        if (BraveBridge.isIncognito(tab) || !url.equals(BraveBridge.urlOf(tab))) return;
                        inject(tab, url, scripts);
                    } catch (Throwable error) {
                        Logger.e("deferred inject failed", error);
                    }
                });
            } catch (Throwable error) {
                Logger.e("deferred match failed", error);
            }
        });
    }

    private static void inject(Object tab, String url, List<Userscript> scripts) {
        if (scripts.isEmpty()) return;
        int ok = 0;
        for (Userscript script : scripts) {
            try {
                if (BraveBridge.run(tab, ScriptInjector.buildPayload(script))) ok++;
            } catch (Throwable error) {
                Logger.e("inject failed", error);
            }
        }
        Logger.i("injected " + ok + "/" + scripts.size() + " script(s) into " + Logger.redact(url));
    }

    private static void promptDirectInstall(Context context, String url) {
        // A page can offer an install review only while Brave is in the foreground, and at most
        // once per PROMPT_MIN_GAP_MS (plus the per-URL dedupe).
        WeakReference<Activity> foreground = resumed;
        if (foreground == null || foreground.get() == null) return;
        long now = SystemClock.elapsedRealtime();
        if (url.equals(lastPromptUrl) && now - lastPromptAt < PROMPT_DEDUPE_MS) return;
        if (now - lastAnyPromptAt < PROMPT_MIN_GAP_MS) return;
        lastPromptUrl = url;
        lastPromptAt = now;
        lastAnyPromptAt = now;
        installFromUrl(null, url);
    }

    private static Handler mainHandler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    private static boolean isMainProcess(Application app) {
        String name = null;
        try {
            if (Build.VERSION.SDK_INT >= 28) name = Application.getProcessName();
        } catch (Throwable ignored) { }
        if (name == null || name.isEmpty()) name = cmdlineProcessName();
        return name != null && name.equals(app.getPackageName());
    }

    private static String cmdlineProcessName() {
        try (InputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[256];
            int n = in.read(buffer);
            if (n <= 0) return null;
            int end = 0;
            while (end < n && buffer[end] != 0) end++;
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
