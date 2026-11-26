/*
 * Brave Userscripts logging. Part of the Brave port of Alastor-Kaneki/Morphe-Patches
 * (GPL-3.0-or-later). SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import android.util.Log;

/**
 * Log tag {@code BraveMonkey}. Never log script source, and log URLs only through
 * {@link #redact(String)} (scheme, host and path; no query, fragment or credentials).
 */
final class Logger {
    static final String TAG = "BraveMonkey";

    private Logger() { }

    static void i(String message) {
        try { Log.i(TAG, message); } catch (Throwable ignored) { }
    }

    static void w(String message) {
        try { Log.w(TAG, message); } catch (Throwable ignored) { }
    }

    static void e(String message, Throwable error) {
        try { Log.e(TAG, message + ": " + describe(error)); } catch (Throwable ignored) { }
    }

    /** Class and message only: a stack trace could carry page or script text. */
    static String describe(Throwable error) {
        if (error == null) return "null";
        String message = error.getMessage();
        if (message != null && message.length() > 200) message = message.substring(0, 200);
        return error.getClass().getName() + (message == null ? "" : ": " + message);
    }

    /** {@code https://host/path} without userinfo, port, query or fragment. Pure; JVM tested. */
    static String redact(String url) {
        if (url == null) return "null";
        int sep = url.indexOf("://");
        if (sep <= 0) {
            int colon = url.indexOf(':');
            return colon > 0 ? url.substring(0, colon) + ":…" : "…";
        }
        String rest = url.substring(sep + 3);
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '?' || c == '#') { cut = i; break; }
        }
        rest = rest.substring(0, cut);
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "/";
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(']') < colon) authority = authority.substring(0, colon);
        if (path.length() > 120) path = path.substring(0, 120) + "…";
        return url.substring(0, sep) + "://" + authority + path;
    }
}
