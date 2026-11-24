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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private static final String BACKUP_FORMAT = "BraveUserscripts-backup";
    private static final Object LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BraveMonkey-io");
        thread.setDaemon(true);
        return thread;
    });
    private static List<Userscript> cache;

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

    static void save(Context context, Userscript script) throws Exception {
        synchronized (LOCK) {
            ensureLoaded(context);
            Userscript parsed = UserscriptMetadataParser.reparsePreservingState(script, script.source);
            parsed.id = script.id == null || script.id.isEmpty() ? parsed.id : script.id;
            parsed.enabled = script.enabled;
            parsed.installUrl = script.installUrl;
            parsed.requireSources.addAll(script.requireSources);
            boolean replaced = false;
            for (int i = 0; i < cache.size(); i++) {
                if (cache.get(i).id.equals(parsed.id)) { cache.set(i, parsed); replaced = true; break; }
            }
            if (!replaced) { parsed.sortOrder = cache.size(); cache.add(parsed); }
            write(context);
        }
    }

    static void remove(Context context, String id) throws Exception {
        synchronized (LOCK) {
            ensureLoaded(context);
            for (int i = cache.size() - 1; i >= 0; i--) if (cache.get(i).id.equals(id)) cache.remove(i);
            write(context);
        }
    }

    static void setEnabled(Context context, String id, boolean enabled) throws Exception {
        synchronized (LOCK) {
            ensureLoaded(context);
            for (Userscript s : cache) if (s.id.equals(id)) s.enabled = enabled;
            write(context);
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
            int changed = 0;
            int failed = 0;
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
                            synchronized (LOCK) {
                                ensureLoaded(context);
                                for (int i = 0; i < cache.size(); i++) {
                                    if (cache.get(i).id.equals(old.id)) cache.set(i, fresh);
                                }
                            }
                            changed++;
                        }
                    } catch (Throwable error) {
                        failed++;
                        Logger.w("update failed for " + Logger.redact(url) + ": " + Logger.describe(error));
                    }
                }
                synchronized (LOCK) { ensureLoaded(context); write(context); }
                callback.done(true, changed + " script(s) updated"
                        + (failed > 0 ? ", " + failed + " failed" : ""), null);
            } catch (Throwable e) { callback.done(false, safe(e), null); }
        });
    }

    static String backup(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", BACKUP_FORMAT);
        root.put("version", 1);
        JSONArray a = new JSONArray();
        for (Userscript s : list(context)) a.put(s.toJson());
        root.put("scripts", a);
        return root.toString(2);
    }

    static boolean isBackup(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.startsWith("{")
                && (trimmed.contains(BACKUP_FORMAT) || trimmed.contains("MonkeyScript-backup"));
    }

    static void restore(Context context, String text) throws Exception {
        JSONArray a = new JSONObject(text).getJSONArray("scripts");
        List<Userscript> scripts = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) scripts.add(Userscript.fromJson(a.getJSONObject(i)));
        synchronized (LOCK) { cache = scripts; write(context); }
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
            if (at >= 0) cache.set(at, s); else { s.sortOrder = cache.size(); cache.add(s); }
            write(context);
        }
    }

    // Caller holds LOCK.
    private static void ensureLoaded(Context c) {
        if (cache != null) return;
        cache = new ArrayList<>();
        File f = new File(new File(c.getFilesDir(), DIR), "scripts.json");
        if (!f.isFile()) return;
        try {
            JSONArray a = new JSONObject(readFile(f)).optJSONArray("scripts");
            if (a != null) for (int i = 0; i < a.length(); i++) cache.add(Userscript.fromJson(a.getJSONObject(i)));
        } catch (Throwable error) {
            Logger.e("cannot read the script database", error);
            cache.clear();
        }
    }

    // Caller holds LOCK.
    private static void write(Context c) throws Exception {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create script database");
        JSONArray a = new JSONArray();
        for (Userscript s : cache) a.put(s.toJson());
        JSONObject r = new JSONObject();
        r.put("scripts", a);
        File tmp = new File(dir, "scripts.tmp"), out = new File(dir, "scripts.json");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            f.write(r.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (out.exists() && !out.delete()) throw new Exception("Cannot replace script database");
        if (!tmp.renameTo(out)) throw new Exception("Cannot save script database");
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
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(b)) != -1) {
            total += n;
            if (total > max) throw new Exception("File is too large");
            o.write(b, 0, n);
        }
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return read(in, 64 * 1024 * 1024); }
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
