/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Private JSON database (filesDir/bravemonkey/scripts.json), settings and the network installer.
 *
 * Brave changes: every download is https only (redirects are followed by hand and each hop must
 * stay https), capped at 4 MB, and only ever started by the user (install review, "Check updates");
 * there is no background update job. Store edits take effect on the next page load.
 */
final class MonkeyStore {
    interface Callback { void done(boolean ok, String message, Userscript script); }

    static final int MAX_DOWNLOAD = 4 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final String PREFS = "app.template.bravemonkey";
    private static final String DIR = "bravemonkey";
    private static final Object LOCK = new Object();
    private static final Map<String, String> PENDING_EXPORTS = new HashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BraveMonkey-io");
        thread.setDaemon(true);
        return thread;
    });
    // Never mutated in place: every change builds a new list and swaps it in after the write.
    private static List<Userscript> cache;
    private static ScriptDatabase database;

    private MonkeyStore() { }

    static void runInBackground(Runnable task) {
        IO.execute(task);
    }

    static boolean isLoaded() {
        synchronized (LOCK) { return cache != null; }
    }

    static List<Userscript> list(Context context) {
        synchronized (LOCK) {
            ensureLoaded(context);
            List<Userscript> out = new ArrayList<>();
            for (Userscript s : cache) out.add(s.copy());
            Collections.sort(out, (a, b) -> Integer.compare(a.sortOrder, b.sortOrder));
            return out;
        }
    }

    static Userscript get(Context context, String id) {
        synchronized (LOCK) {
            ensureLoaded(context);
            for (Userscript s : cache) if (s.id.equals(id)) return s.copy();
            return null;
        }
    }

    /**
     * Saves without network access. {@code script} carries the previously stored {@code requires}
     * and {@code requireSources}; its {@code source} is reparsed. Fails when the source declares a
     * @require whose body is not stored yet (use {@link #saveAsync}).
     */
    static void save(Context context, Userscript script) throws Exception {
        Userscript parsed = reparseForSave(script);
        List<String> bodies = ScriptDatabase.reconcileRequires(
                script.requires, script.requireSources, parsed.requires);
        if (!ScriptDatabase.missing(parsed.requires, bodies).isEmpty()) {
            throw new Exception("New @require files must be downloaded first; save from the editor");
        }
        parsed.requireSources.addAll(bodies);
        upsert(context, parsed);
    }

    /**
     * Editor save on the IO thread: keeps stored @require bodies for URLs that are still declared
     * (in declared order), drops removed ones and downloads new ones (https only). Nothing is
     * saved when a download fails.
     */
    static void saveAsync(Context context, Userscript script, Callback callback) {
        IO.execute(() -> {
            try {
                Userscript parsed = reparseForSave(script);
                List<String> bodies = ScriptDatabase.reconcileRequires(
                        script.requires, script.requireSources, parsed.requires);
                for (int i = 0; i < bodies.size(); i++) {
                    if (bodies.get(i) != null) continue;
                    String url = parsed.requires.get(i);
                    if (!ViolentmonkeyCompat.isHttps(url)) {
                        throw new Exception("Not saved: @require must use https: " + Logger.redact(url));
                    }
                    try {
                        bodies.set(i, fetch(url));
                    } catch (Throwable error) {
                        throw new Exception("Not saved: cannot download @require "
                                + Logger.redact(url) + " (" + safe(error) + ")");
                    }
                }
                parsed.requireSources.addAll(bodies);
                upsert(context, parsed);
                callback.done(true, "Saved " + parsed.name, get(context, parsed.id));
            } catch (Throwable e) { callback.done(false, safe(e), null); }
        });
    }

    private static Userscript reparseForSave(Userscript script) {
        Userscript parsed = UserscriptMetadataParser.reparsePreservingState(script, script.source);
        parsed.id = script.id == null || script.id.isEmpty() ? parsed.id : script.id;
        parsed.enabled = script.enabled;
        parsed.installUrl = script.installUrl;
        return parsed;
    }

    private static void upsert(Context context, Userscript parsed) throws Exception {
        synchronized (LOCK) {
            ensureLoaded(context);
            List<Userscript> next = new ArrayList<>(cache);
            boolean replaced = false;
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).id.equals(parsed.id)) { next.set(i, parsed); replaced = true; break; }
            }
            if (!replaced) { parsed.sortOrder = next.size(); next.add(parsed); }
            commit(context, next);
        }
    }

    static void remove(Context context, String id) throws Exception {
        synchronized (LOCK) {
            ensureLoaded(context);
            List<Userscript> next = new ArrayList<>(cache);
            for (int i = next.size() - 1; i >= 0; i--) if (next.get(i).id.equals(id)) next.remove(i);
            commit(context, next);
        }
    }

    static void setEnabled(Context context, String id, boolean enabled) throws Exception {
        synchronized (LOCK) {
            ensureLoaded(context);
            List<Userscript> next = new ArrayList<>(cache.size());
            for (Userscript s : cache) {
                if (s.id.equals(id)) {
                    Userscript changed = s.copy();
                    changed.enabled = enabled;
                    next.add(changed);
                } else {
                    next.add(s);
                }
            }
            commit(context, next);
        }
    }

    /** Enabled scripts matching an http(s) URL; empty when switched off globally or for the host. */
    static List<Userscript> matching(Context context, String url) {
        List<Userscript> out = new ArrayList<>();
        if (!globalEnabled(context) || hostDisabled(context, url)) return out;
        synchronized (LOCK) {
            ensureLoaded(context);
            for (Userscript s : cache) if (UrlPatternMatcher.matches(s, url)) out.add(s.copy());
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.sortOrder, b.sortOrder));
        return out;
    }

    /** Loads the database on the IO thread so the first page load does not read it on main. */
    static void warmUp(Context context) {
        IO.execute(() -> {
            try {
                synchronized (LOCK) { ensureLoaded(context); }
            } catch (Throwable error) {
                Logger.e("warm-up failed", error);
            }
        });
    }

    static void importText(Context context, String source, String name, String installUrl, Callback callback) {
        IO.execute(() -> {
            try {
                Userscript s = UserscriptMetadataParser.parse(source, name, installUrl);
                loadRequires(s);
                store(context, s);
                callback.done(true, "Installed " + s.name, s.copy());
            } catch (Throwable e) { callback.done(false, safe(e), null); }
        });
    }

    static void updateAll(Context context, Callback callback) {
        IO.execute(() -> {
            int failed = 0;
            Map<String, Userscript> updates = new HashMap<>();
            try {
                for (Userscript old : list(context)) {
                    String url = old.effectiveUpdateUrl();
                    // A .meta.js @updateURL holds only the header; fetch the full script instead.
                    if (url.toLowerCase(Locale.US).split("[?#]", 2)[0].endsWith(".meta.js")) {
                        url = old.installUrl == null ? "" : old.installUrl.trim();
                    }
                    if (url.isEmpty()) continue;
                    try {
                        String text = fetch(url);
                        if (!ViolentmonkeyCompat.looksLikeUserscript(text)) {
                            throw new Exception("not a userscript");
                        }
                        Userscript fresh = UserscriptMetadataParser.parse(text, fileName(url), old.installUrl);
                        if (compareVersions(fresh.version, old.version) > 0) {
                            fresh.id = old.id;
                            fresh.enabled = old.enabled;
                            fresh.installedAt = old.installedAt;
                            fresh.sortOrder = old.sortOrder;
                            loadRequires(fresh);
                            updates.put(old.id, fresh);
                        }
                    } catch (Throwable error) {
                        failed++;
                        Logger.w("update failed for " + Logger.redact(url) + ": " + Logger.describe(error));
                    }
                }
                int changed = 0;
                if (!updates.isEmpty()) {
                    synchronized (LOCK) {
                        ensureLoaded(context);
                        List<Userscript> next = new ArrayList<>(cache.size());
                        for (Userscript s : cache) {
                            Userscript fresh = updates.get(s.id);
                            if (fresh != null) changed++;
                            next.add(fresh != null ? fresh : s);
                        }
                        commit(context, next);
                    }
                }
                callback.done(true, changed + " script(s) updated"
                        + (failed > 0 ? ", " + failed + " failed" : ""), null);
            } catch (Throwable e) { callback.done(false, safe(e), null); }
        });
    }

    /** Backup JSON; throws (nothing written) above {@link ScriptDatabase#MAX_BACKUP_BYTES}. */
    static String backup(Context context) throws Exception {
        return ScriptDatabase.backupJson(list(context));
    }

    /** Replaces the whole collection with an already validated backup ({@link ScriptDatabase#parseBackup}). */
    static void restore(Context context, List<Userscript> scripts) throws Exception {
        List<Userscript> next = new ArrayList<>();
        for (Userscript s : scripts) next.add(s.copy());
        synchronized (LOCK) {
            ensureLoaded(context);
            commit(context, next);
        }
    }

    /** Export text kept outside the Activity so a configuration change does not lose it. */
    static void putPendingExport(String key, String content) {
        synchronized (PENDING_EXPORTS) { PENDING_EXPORTS.put(key, content); }
    }

    static String takePendingExport(String key) {
        synchronized (PENDING_EXPORTS) { return PENDING_EXPORTS.remove(key); }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean globalEnabled(Context c) { return prefs(c).getBoolean("enabled", true); }

    static void globalEnabled(Context c, boolean v) { prefs(c).edit().putBoolean("enabled", v).apply(); }

    static boolean hostDisabled(Context c, String url) {
        String h = host(url);
        return !h.isEmpty() && disabledHosts(c).contains(h);
    }

    static Set<String> disabledHosts(Context c) {
        return new HashSet<>(prefs(c).getStringSet("disabled_hosts", Collections.<String>emptySet()));
    }

    static void disabledHosts(Context c, Set<String> hosts) {
        prefs(c).edit().putStringSet("disabled_hosts", new HashSet<>(hosts)).apply();
    }

    private static void store(Context context, Userscript s) throws Exception {
        synchronized (LOCK) {
            ensureLoaded(context);
            int at = -1;
            for (int i = 0; i < cache.size(); i++) {
                if (cache.get(i).id.equals(s.id)) {
                    at = i;
                    s.installedAt = cache.get(i).installedAt;
                    s.enabled = cache.get(i).enabled;
                    s.sortOrder = cache.get(i).sortOrder;
                }
            }
            List<Userscript> next = new ArrayList<>(cache);
            if (at >= 0) next.set(at, s); else { s.sortOrder = next.size(); next.add(s); }
            commit(context, next);
        }
    }

    // Caller holds LOCK.
    private static void ensureLoaded(Context c) {
        if (cache != null) return;
        cache = database(c).current();
    }

    // Caller holds LOCK. Publishes next only after it is durably on disk; on failure the cache and
    // scripts.json both keep the previous collection and the caller gets the exception.
    private static void commit(Context c, List<Userscript> next) throws Exception {
        ScriptDatabase db = database(c);
        db.commit(next);
        cache = db.current();
    }

    // Caller holds LOCK.
    private static ScriptDatabase database(Context c) {
        if (database == null) {
            database = new ScriptDatabase(new File(c.getFilesDir(), DIR), ScriptDatabase.REAL);
        }
        return database;
    }

    private static void loadRequires(Userscript s) throws Exception {
        s.requireSources.clear();
        for (String u : s.requires) {
            if (!ViolentmonkeyCompat.isHttps(u)) {
                throw new Exception("@require must use https: " + Logger.redact(u));
            }
            s.requireSources.add(fetch(u));
        }
    }

    /** https-only GET with manual redirects (each hop https) and a 4 MB cap. Never on main. */
    static String fetch(String value) throws Exception {
        String current = value == null ? "" : value.trim();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!ViolentmonkeyCompat.isHttps(current)) {
                throw new Exception("Only https:// downloads are allowed");
            }
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Brave Userscripts");
                int status = c.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new Exception("HTTP " + status);
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (status < 200 || status >= 300) throw new Exception("HTTP " + status);
                // -1 when unknown or above Integer.MAX_VALUE; read() enforces the cap either way.
                int length = c.getContentLength();
                if (length > MAX_DOWNLOAD) throw new Exception("File is too large");
                try (InputStream in = c.getInputStream()) {
                    return read(in, MAX_DOWNLOAD);
                }
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("Too many redirects");
    }

    static String read(InputStream in, int max) throws Exception {
        return ScriptDatabase.read(in, max);
    }

    static String fileName(String u) {
        try {
            String p = Uri.parse(u).getLastPathSegment();
            if (p == null || p.trim().isEmpty()) return "userscript.user.js";
            return p.toLowerCase(Locale.US).contains(".user.") ? p : p + ".user.js";
        } catch (Throwable e) {
            return "userscript.user.js";
        }
    }

    static String host(String u) {
        try {
            String h = Uri.parse(u).getHost();
            return h == null ? "" : h.toLowerCase(Locale.US);
        } catch (Throwable e) {
            return "";
        }
    }

    static String safe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            try {
                int d = Integer.compare(Integer.parseInt(p), Integer.parseInt(q));
                if (d != 0) return d;
            } catch (NumberFormatException e) {
                int d = p.compareToIgnoreCase(q);
                if (d != 0) return d;
            }
        }
        return 0;
    }
}
