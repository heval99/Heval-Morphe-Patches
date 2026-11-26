/*
 * Brave Userscripts script database. Part of the Brave port of Alastor-Kaneki/Morphe-Patches
 * (GPL-3.0-or-later). SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java (no Context) part of the store, JVM tested: the crash-safe scripts.json file, the
 * backup format and the @require reconciliation.
 *
 * Writes go to scripts.json.tmp (fsynced), which then atomically replaces scripts.json; where an
 * atomic move is unavailable the old file is kept as scripts.json.bak until the new one is in
 * place. A readable scripts.json always wins on load; otherwise the newest valid .tmp/.bak is
 * promoted. Callers publish a new in-memory state only after {@link #write} returned.
 */
final class ScriptDatabase {
    /** One cap for the database file, backup export and backup import (UTF-8 bytes). */
    static final int MAX_BACKUP_BYTES = 64 * 1024 * 1024;
    static final String BACKUP_FORMAT = "BraveUserscripts-backup";
    static final String LEGACY_BACKUP_FORMAT = "MonkeyScript-backup";
    static final int BACKUP_VERSION = 1;
    static final String FILE = "scripts.json";

    /** File-system seam; tests inject failures. */
    interface FileOps {
        void writeSynced(File file, byte[] data) throws IOException;
        /** Atomic rename over an existing target; throws when unsupported or failed. */
        void atomicReplace(File from, File to) throws Throwable;
        boolean rename(File from, File to);
        boolean delete(File file);
    }

    static final FileOps REAL = new FileOps() {
        @Override public void writeSynced(File file, byte[] data) throws IOException {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
                out.flush();
                out.getFD().sync();
            }
        }

        // java.nio.file exists from API 26; an older runtime throws NoClassDefFoundError,
        // which write() treats like any other failed atomic move.
        @SuppressWarnings("NewApi")
        @Override public void atomicReplace(File from, File to) throws Throwable {
            Files.move(from.toPath(), to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override public boolean rename(File from, File to) { return from.renameTo(to); }

        @Override public boolean delete(File file) { return file.delete(); }
    };

    private final File dir;
    private final FileOps ops;

    ScriptDatabase(File dir, FileOps ops) {
        this.dir = dir;
        this.ops = ops;
    }

    // Published state. Never mutated in place; replaced only after a successful write.
    private List<Userscript> current;

    /** The live collection (loaded on first use). Callers must not mutate it. Caller holds the store lock. */
    List<Userscript> current() {
        if (current == null) current = load();
        return current;
    }

    /** Writes {@code next} and only then publishes it; on failure {@link #current()} is unchanged. */
    void commit(List<Userscript> next) throws Exception {
        write(next);
        current = next;
    }

    File main() { return new File(dir, FILE); }

    File tmp() { return new File(dir, FILE + ".tmp"); }

    File bak() { return new File(dir, FILE + ".bak"); }

    /** Durably replaces scripts.json with {@code scripts}; on failure the old file is intact. */
    void write(List<Userscript> scripts) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Cannot create script database");
        JSONArray array = new JSONArray();
        for (Userscript s : scripts) array.put(s.toJson());
        byte[] data = new JSONObject().put("scripts", array).toString(2)
                .getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BACKUP_BYTES) {
            throw new Exception("The scripts would exceed " + megabytes() + " MB; change not saved");
        }
        File tmp = tmp(), main = main(), bak = bak();
        try {
            ops.writeSynced(tmp, data);
        } catch (Throwable error) {
            ops.delete(tmp);
            throw new Exception("Cannot save script database", error);
        }
        try {
            ops.atomicReplace(tmp, main);
            ops.delete(bak);
            return;
        } catch (Throwable error) {
            Logger.w("atomic database move unavailable: " + Logger.describe(error));
        }
        // Fallback: old -> .bak, tmp -> scripts.json, then drop .bak. Load recovers any midpoint.
        if (main.exists()) {
            if (bak.exists() && !ops.delete(bak)) {
                ops.delete(tmp);
                throw new Exception("Cannot replace script database");
            }
            if (!ops.rename(main, bak)) {
                ops.delete(tmp);
                throw new Exception("Cannot replace script database");
            }
        }
        if (!ops.rename(tmp, main)) {
            // Put the old file back so the on-disk state matches the caller's unchanged cache.
            if (bak.exists() && !main.exists()) ops.rename(bak, main);
            ops.delete(tmp);
            throw new Exception("Cannot save script database");
        }
        ops.delete(bak);
    }

    /**
     * Loads scripts.json, or when it is missing or unreadable the newest valid scripts.json.tmp /
     * .bak (promoted to scripts.json). An unreadable scripts.json is kept as scripts.json.corrupt.
     * Returns an empty list when nothing usable exists.
     */
    List<Userscript> load() {
        File main = main();
        if (main.isFile()) {
            try {
                return parseDatabase(readFile(main));
            } catch (Throwable error) {
                Logger.e("cannot read the script database", error);
            }
        }
        File best = null;
        List<Userscript> recovered = null;
        for (File candidate : new File[]{tmp(), bak()}) {
            if (!candidate.isFile()) continue;
            List<Userscript> parsed;
            try {
                parsed = parseDatabase(readFile(candidate));
            } catch (Throwable error) {
                continue;
            }
            // tmp is listed first, so on equal timestamps the newer write wins.
            if (best == null || candidate.lastModified() > best.lastModified()) {
                best = candidate;
                recovered = parsed;
            }
        }
        if (best == null) {
            if (main.isFile()) ops.rename(main, new File(dir, FILE + ".corrupt"));
            return new ArrayList<>();
        }
        Logger.w("script database recovered from " + best.getName());
        if (main.isFile()) {
            File corrupt = new File(dir, FILE + ".corrupt");
            ops.delete(corrupt);
            ops.rename(main, corrupt);
        }
        if (!main.exists() && !ops.rename(best, main)) {
            Logger.w("cannot promote " + best.getName());
        }
        return recovered;
    }

    static List<Userscript> parseDatabase(String text) throws Exception {
        JSONArray array = new JSONObject(text).getJSONArray("scripts");
        List<Userscript> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) out.add(Userscript.fromJson(array.getJSONObject(i)));
        return out;
    }

    // ---- Backups ----

    /** Backup JSON, refused (before anything is written) above {@link #MAX_BACKUP_BYTES}. */
    static String backupJson(List<Userscript> scripts) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", BACKUP_FORMAT);
        root.put("version", BACKUP_VERSION);
        JSONArray array = new JSONArray();
        for (Userscript s : scripts) array.put(s.toJson());
        root.put("scripts", array);
        String text = root.toString(2);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BACKUP_BYTES) {
            throw new Exception("Backup is larger than " + megabytes() + " MB; export scripts one by one");
        }
        return text;
    }

    /**
     * True for a JSON object: a userscript or userstyle starts with its comment header, never with
     * "{". Such a file is then either a valid backup or an error, never a script install.
     */
    static boolean looksLikeBackup(String text) {
        return text != null && text.trim().startsWith("{");
    }

    /**
     * Validates the whole backup before anything is replaced: a known {@code format} with a
     * supported {@code version}, or (older backups) exactly one key {@code scripts}; every entry
     * must be an object with a non-empty {@code id} and a {@code source} string.
     */
    static List<Userscript> parseBackup(String text) throws Exception {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BACKUP_BYTES) {
            throw new Exception("Backup is larger than " + megabytes() + " MB");
        }
        JSONObject root;
        try {
            root = new JSONObject(text.trim());
        } catch (Throwable error) {
            throw new Exception("Not a Brave Userscripts backup");
        }
        if (root.has("format")) {
            String format = root.optString("format", "");
            if (!BACKUP_FORMAT.equals(format) && !LEGACY_BACKUP_FORMAT.equals(format)) {
                throw new Exception("Not a Brave Userscripts backup");
            }
            Object version = root.opt("version");
            if (!(version instanceof Integer || version instanceof Long)) {
                throw new Exception("Backup has no version");
            }
            long v = ((Number) version).longValue();
            if (v < 1 || v > BACKUP_VERSION) {
                throw new Exception("Unsupported backup version " + v);
            }
        } else {
            Iterator<String> keys = root.keys();
            if (!keys.hasNext() || !"scripts".equals(keys.next()) || keys.hasNext()) {
                throw new Exception("Not a Brave Userscripts backup");
            }
        }
        JSONArray array = root.optJSONArray("scripts");
        if (array == null) throw new Exception("Backup has no scripts list");
        List<Userscript> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null
                    || entry.optString("id", "").trim().isEmpty()
                    || !(entry.opt("source") instanceof String)) {
                throw new Exception("Backup entry " + (i + 1) + " is not a userscript");
            }
            out.add(Userscript.fromJson(entry));
        }
        return out;
    }

    // ---- @require reconciliation ----

    /**
     * Bodies for {@code newUrls} in order, reusing a stored body only for a URL that is still
     * declared. {@code null} marks a URL that must be fetched. The stored pairing is trusted only
     * when {@code oldUrls} and {@code oldBodies} have the same length (they are built in lockstep).
     */
    static List<String> reconcileRequires(List<String> oldUrls, List<String> oldBodies, List<String> newUrls) {
        Map<String, String> known = new HashMap<>();
        if (oldUrls.size() == oldBodies.size()) {
            for (int i = 0; i < oldUrls.size(); i++) known.put(oldUrls.get(i), oldBodies.get(i));
        }
        List<String> out = new ArrayList<>(newUrls.size());
        for (String url : newUrls) out.add(known.get(url));
        return out;
    }

    static List<String> missing(List<String> urls, List<String> bodies) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) if (bodies.get(i) == null) out.add(urls.get(i));
        return Collections.unmodifiableList(out);
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return read(in, MAX_BACKUP_BYTES); }
    }

    static String read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(b)) != -1) {
            total += n;
            if (total > max) throw new Exception("File is too large");
            o.write(b, 0, n);
        }
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int megabytes() { return MAX_BACKUP_BYTES / (1024 * 1024); }
}
