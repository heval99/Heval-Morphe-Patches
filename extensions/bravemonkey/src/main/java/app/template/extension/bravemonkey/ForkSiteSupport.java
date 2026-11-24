/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.net.URI;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Greasy Fork / Sleazy Fork browsing and install-URL resolution.
 *
 * Brave changes: the Greasy Fork publish feature (queuePublish, injectPendingPublish, the prefill
 * POST) is removed; the hash-marker and URL-poller install paths are removed; every URL handled
 * here must pass {@link ViolentmonkeyCompat#isAllowedInstallRequest} and every fetch is https.
 */
final class ForkSiteSupport {
    static final String GREASY_HOST = "greasyfork.org";
    static final String SLEAZY_HOST = "sleazyfork.org";
    static final String EXTRA_URL = "script_page_url";

    private static final Pattern SCRIPT_ID = Pattern.compile("/scripts/(\\d+)(?:[-/]|$)");
    private static final Pattern HREF = Pattern.compile("(?is)href\\s*=\\s*([\"'])(.*?)\\1");

    private ForkSiteSupport() { }

    static boolean isForkPage(String url) {
        try {
            return ViolentmonkeyCompat.isForkHost(new URI(url).getHost());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Runs on a background thread: page URL -> https URL of the .user.js/.user.css file. */
    static String resolveInstallUrl(String pageUrl) throws Exception {
        if (!ViolentmonkeyCompat.isAllowedInstallRequest(pageUrl)) {
            throw new Exception("Only https userscript links or Greasy Fork / Sleazy Fork script pages can be installed");
        }
        if (ViolentmonkeyCompat.isDirectUserscriptUrl(pageUrl)) return pageUrl;

        String apiUrl = apiUrl(pageUrl);
        if (apiUrl != null) {
            try {
                JSONObject object = new JSONObject(MonkeyStore.fetch(apiUrl));
                String codeUrl = object.optString("code_url", "").trim();
                if (isAllowedForkInstallUrl(codeUrl)) return codeUrl;
            } catch (Throwable error) {
                Logger.w("Fork API lookup failed: " + Logger.describe(error));
            }
        }

        String html = MonkeyStore.fetch(pageUrl);
        Matcher matcher = HREF.matcher(html);
        while (matcher.find()) {
            String href = htmlDecode(matcher.group(2));
            String resolved;
            try {
                resolved = new URL(new URL(pageUrl), href).toString();
            } catch (Exception ignored) {
                continue;
            }
            if (isAllowedForkInstallUrl(resolved)) return resolved;
        }

        return ViolentmonkeyCompat.fallbackForkInstallUrl(pageUrl);
    }

    static void openSite(Activity activity, String host) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://" + ViolentmonkeyCompat.normalizeHost(host) + "/en/scripts"));
        intent.setPackage(activity.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    /** Opens the install review; refuses (with a toast) anything the validator rejects. */
    static void openInstallPreview(Activity activity, String pageUrl) {
        String url = pageUrl == null ? "" : pageUrl.trim();
        if (!ViolentmonkeyCompat.isAllowedInstallRequest(url)) {
            MonkeyUi.toast(activity, "Only https userscript links (.user.js / .user.css) or Greasy Fork / Sleazy Fork script pages");
            return;
        }
        activity.startActivity(new Intent(activity, UserscriptInstallActivity.class)
                .putExtra(EXTRA_URL, url));
    }

    private static String apiUrl(String pageUrl) {
        try {
            URI uri = new URI(pageUrl);
            String host = ViolentmonkeyCompat.normalizeHost(uri.getHost());
            Matcher matcher = SCRIPT_ID.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!matcher.find()) return null;
            String apiHost = GREASY_HOST.equals(host) ? "api.greasyfork.org" : host;
            return "https://" + apiHost + "/en/scripts/" + matcher.group(1) + ".json";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isAllowedForkInstallUrl(String url) {
        if (!ViolentmonkeyCompat.isHttps(url) || !ViolentmonkeyCompat.isDirectUserscriptUrl(url)) {
            return false;
        }
        try {
            String host = ViolentmonkeyCompat.normalizeHost(new URI(url).getHost());
            return ViolentmonkeyCompat.isForkHost(host)
                    || "update.greasyfork.org".equals(host)
                    || "update.sleazyfork.org".equals(host);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String htmlDecode(String value) {
        return value.replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
