/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Full-screen install review. The only Userscripts screen reachable from outside the app
 * (exported, deep link {@code bravemonkey-install://install?url=<https url>}). Nothing is saved
 * until the user taps Install.
 *
 * Brave changes: the URL from the deep link or the {@code script_page_url} extra must pass
 * {@link ViolentmonkeyCompat#isAllowedInstallRequest} before anything is fetched; the download is
 * https only and must look like a userscript; the review shows the source, the site rules,
 * @require URLs and whether the host is a known userscript catalogue.
 */
public final class UserscriptInstallActivity extends Activity {
    private TextView status;
    private TextView install;
    private TextView sourceView;
    private Userscript parsed;
    private String resolvedUrl;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MonkeyUi.window(this);
        render();

        String pageUrl = requestedUrl(getIntent());
        if (pageUrl == null) {
            fail("No userscript URL was supplied");
            return;
        }
        if (!ViolentmonkeyCompat.isAllowedInstallRequest(pageUrl)) {
            fail("Only https userscript links (.user.js / .user.css) or Greasy Fork / Sleazy Fork script pages can be installed");
            return;
        }
        load(pageUrl);
    }

    private static String requestedUrl(Intent intent) {
        if (intent == null) return null;
        String url = null;
        try {
            url = intent.getStringExtra(ForkSiteSupport.EXTRA_URL);
        } catch (Throwable ignored) { }
        Uri data = intent.getData();
        if ((url == null || url.trim().isEmpty()) && data != null
                && ScriptInjector.INSTALL_SCHEME.equalsIgnoreCase(data.getScheme())) {
            try {
                url = data.getQueryParameter("url");
            } catch (Throwable ignored) { }
        }
        return url == null || url.trim().isEmpty() ? null : url.trim();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        root.setBackgroundColor(MonkeyUi.bg(this));

        root.addView(MonkeyUi.label(this, "Install userscript", 26, true, MonkeyUi.text(this)));
        status = MonkeyUi.label(this, "Resolving script…", 14, false, MonkeyUi.muted(this));
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        sourceView = MonkeyUi.label(this, "", 12, false, MonkeyUi.text(this));
        sourceView.setTypeface(Typeface.MONOSPACE);
        sourceView.setTextIsSelectable(true);
        sourceView.setPadding(dp(14), dp(14), dp(14), dp(14));
        sourceView.setBackground(MonkeyUi.card(this));
        scroll.addView(sourceView, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        TextView cancel = MonkeyUi.button(this, "Cancel", false);
        cancel.setOnClickListener(view -> finish());
        actions.addView(cancel, params(-2, -2, 8, 8));

        install = MonkeyUi.button(this, "Install", true);
        install.setEnabled(false);
        install.setAlpha(0.45f);
        install.setOnClickListener(view -> install());
        actions.addView(install, params(-2, -2, 8, 0));
        root.addView(actions);
        setContentView(root);
    }

    private void load(String pageUrl) {
        MonkeyStore.runInBackground(() -> {
            try {
                String url = ForkSiteSupport.resolveInstallUrl(pageUrl);
                String source = MonkeyStore.fetch(url);
                if (!ViolentmonkeyCompat.looksLikeUserscript(source)) {
                    throw new Exception("The downloaded file is not a valid userscript metadata block");
                }
                Userscript script = UserscriptMetadataParser.parse(source, MonkeyStore.fileName(url), url);
                onUi(() -> ready(url, script));
            } catch (Throwable error) {
                Logger.w("install review failed for " + Logger.redact(pageUrl) + ": " + Logger.describe(error));
                onUi(() -> fail(MonkeyStore.safe(error)));
            }
        });
    }

    private void onUi(Runnable action) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            action.run();
        });
    }

    private void ready(String url, Userscript script) {
        resolvedUrl = url;
        parsed = script;
        String kind = Userscript.KIND_CSS.equals(script.kind)
                ? "CSS userstyle"
                : "JavaScript userscript";
        StringBuilder text = new StringBuilder()
                .append(script.name).append('\n')
                .append(kind).append(" • v").append(script.version).append(" • ")
                .append(script.matches.size() + script.includes.size()).append(" site rule(s)\n")
                .append("From ").append(Logger.redact(url))
                .append(ViolentmonkeyCompat.isTrustedInstallUrl(url)
                        ? " (known userscript host)" : " (unknown host: review the code)");
        if (!script.description.isEmpty()) text.append('\n').append(script.description);
        if (!script.allPositiveRules().isEmpty()) {
            text.append("\nRuns on: ").append(join(script.allPositiveRules(), 6));
        }
        if (!script.requires.isEmpty()) {
            text.append("\nAlso downloads (@require): ").append(join(script.requires, 6));
        }
        if (script.matches.isEmpty() && script.includes.isEmpty()) {
            text.append("\nNo @match/@include rule: it will be installed disabled.");
        }
        status.setText(text.toString());
        sourceView.setText(script.source);
        install.setEnabled(true);
        install.setAlpha(1f);
    }

    private void fail(String message) {
        status.setText("Cannot install userscript\n"
                + (message == null ? "Unknown error" : message));
        sourceView.setText("");
        install.setEnabled(false);
        install.setAlpha(0.45f);
    }

    private void install() {
        if (parsed == null || resolvedUrl == null) return;
        install.setEnabled(false);
        status.setText("Installing " + parsed.name + "…");
        MonkeyStore.importText(
                this,
                parsed.source,
                MonkeyStore.fileName(resolvedUrl),
                resolvedUrl,
                (ok, message, script) -> onUi(() -> {
                    MonkeyUi.toast(this, message);
                    if (ok) finish();
                    else install.setEnabled(true);
                })
        );
    }

    private static String join(java.util.List<String> values, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.size() && i < max; i++) {
            if (i > 0) b.append(", ");
            b.append(values.get(i));
        }
        if (values.size() > max) b.append(" (+").append(values.size() - max).append(" more)");
        return b.toString();
    }

    private LinearLayout.LayoutParams params(int width, int height, int top, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, dp(top), dp(right), 0);
        return params;
    }

    private int dp(int value) {
        return MonkeyUi.dp(this, value);
    }
}
