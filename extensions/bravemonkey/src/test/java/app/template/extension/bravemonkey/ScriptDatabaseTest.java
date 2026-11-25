package app.template.extension.bravemonkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** E01 crash-safe database file, E02 @require reconciliation, E03 backup limit and header. */
public class ScriptDatabaseTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Delegates to the real file system; individual steps can be made to fail. */
    private static class FaultyOps implements ScriptDatabase.FileOps {
        boolean failWrite;
        boolean failAtomic;
        boolean failRenameTmp;

        @Override public void writeSynced(File file, byte[] data) throws IOException {
            if (failWrite) {
                Files.write(file.toPath(), Arrays.copyOf(data, data.length / 2)); // torn write
                throw new IOException("disk full");
            }
            ScriptDatabase.REAL.writeSynced(file, data);
        }

        @Override public void atomicReplace(File from, File to) throws Throwable {
            if (failAtomic) throw new UnsupportedOperationException("no atomic move");
            ScriptDatabase.REAL.atomicReplace(from, to);
        }

        @Override public boolean rename(File from, File to) {
            if (failRenameTmp && from.getName().endsWith(".tmp")) return false;
            return ScriptDatabase.REAL.rename(from, to);
        }

        @Override public boolean delete(File file) { return ScriptDatabase.REAL.delete(file); }
    }

    private static Userscript script(String id) {
        Userscript s = new Userscript();
        s.id = id;
        s.name = id;
        s.source = "// " + id;
        return s;
    }

    private static List<String> ids(List<Userscript> scripts) {
        List<String> out = new ArrayList<>();
        for (Userscript s : scripts) out.add(s.id);
        return out;
    }

    private static String dbText(String... ids) throws Exception {
        JSONArray array = new JSONArray();
        for (String id : ids) array.put(script(id).toJson());
        return new JSONObject().put("scripts", array).toString();
    }

    private static void put(File file, String text, long modified) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(modified));
    }

    // ---- E01 ----

    @Test
    public void writeAndReloadRoundTrip() throws Exception {
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL);
        db.write(Arrays.asList(script("a"), script("b")));
        db.write(Collections.singletonList(script("c")));
        assertEquals(Collections.singletonList("c"), ids(new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL).load()));
        assertFalse(db.tmp().exists());
        assertFalse(db.bak().exists());
    }

    @Test
    public void failedWriteKeepsTheOldFile() throws Exception {
        FaultyOps ops = new FaultyOps();
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ops);
        db.write(Collections.singletonList(script("old")));
        ops.failWrite = true;
        try {
            db.write(Collections.singletonList(script("new")));
            fail("write should fail");
        } catch (Exception expected) { }
        assertFalse(db.tmp().exists());
        assertEquals(Collections.singletonList("old"), ids(db.load()));
    }

    @Test
    public void failedCommitKeepsThePublishedStateOnWriteFailure() throws Exception {
        FaultyOps ops = new FaultyOps();
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ops);
        db.commit(Collections.singletonList(script("old")));
        ops.failWrite = true;
        try {
            db.commit(Arrays.asList(script("old"), script("new")));
            fail("commit should fail");
        } catch (Exception expected) { }
        assertEquals(Collections.singletonList("old"), ids(db.current()));
        assertEquals(Collections.singletonList("old"), ids(new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL).load()));
    }

    @Test
    public void failedCommitKeepsThePublishedStateOnRenameFailure() throws Exception {
        FaultyOps ops = new FaultyOps();
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ops);
        db.commit(Collections.singletonList(script("old")));
        ops.failAtomic = true;
        ops.failRenameTmp = true;
        try {
            db.commit(Collections.<Userscript>emptyList());
            fail("commit should fail");
        } catch (Exception expected) { }
        assertEquals(Collections.singletonList("old"), ids(db.current()));
        assertEquals(Collections.singletonList("old"), ids(new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL).load()));
    }

    @Test
    public void fallbackWithoutAtomicMoveReplacesTheFile() throws Exception {
        FaultyOps ops = new FaultyOps();
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ops);
        db.write(Collections.singletonList(script("old")));
        ops.failAtomic = true;
        db.write(Collections.singletonList(script("new")));
        assertFalse(db.bak().exists());
        assertFalse(db.tmp().exists());
        assertEquals(Collections.singletonList("new"), ids(db.load()));
    }

    @Test
    public void failedFallbackRenameRestoresTheOldFile() throws Exception {
        FaultyOps ops = new FaultyOps();
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ops);
        db.write(Collections.singletonList(script("old")));
        ops.failAtomic = true;
        ops.failRenameTmp = true;
        try {
            db.write(Collections.singletonList(script("new")));
            fail("write should fail");
        } catch (Exception expected) { }
        assertTrue(db.main().isFile());
        assertFalse(db.tmp().exists());
        assertEquals(Collections.singletonList("old"), ids(db.load()));
    }

    @Test
    public void recoversTheNewestValidTmpWhenMainIsMissing() throws Exception {
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL);
        // Interrupted fallback: old renamed to .bak, new .tmp complete, scripts.json gone.
        put(db.bak(), dbText("old"), 1_000_000L);
        put(db.tmp(), dbText("new"), 2_000_000L);
        assertEquals(Collections.singletonList("new"), ids(db.load()));
        assertTrue(db.main().isFile()); // promoted
        assertEquals(Collections.singletonList("new"), ids(new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL).load()));
    }

    @Test
    public void tornTmpFallsBackToBak() throws Exception {
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL);
        put(db.bak(), dbText("old"), 1_000_000L);
        put(db.tmp(), "{\"scripts\":[{\"id\":", 2_000_000L);
        assertEquals(Collections.singletonList("old"), ids(db.load()));
    }

    @Test
    public void newerBakWinsOverOlderTmp() throws Exception {
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL);
        put(db.tmp(), dbText("stale"), 1_000_000L);
        put(db.bak(), dbText("fresh"), 2_000_000L);
        assertEquals(Collections.singletonList("fresh"), ids(db.load()));
    }

    @Test
    public void corruptMainIsKeptAsideAndBakRecovered() throws Exception {
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL);
        put(db.main(), "not json", 3_000_000L);
        put(db.bak(), dbText("old"), 1_000_000L);
        assertEquals(Collections.singletonList("old"), ids(db.load()));
        assertTrue(new File(folder.getRoot(), "scripts.json.corrupt").isFile());
        assertEquals(Collections.singletonList("old"), ids(new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL).load()));
    }

    @Test
    public void validMainWinsOverLeftovers() throws Exception {
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL);
        put(db.main(), dbText("main"), 1_000_000L);
        put(db.tmp(), dbText("tmp"), 2_000_000L);
        assertEquals(Collections.singletonList("main"), ids(db.load()));
    }

    @Test
    public void emptyFolderLoadsEmpty() {
        assertTrue(new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL).load().isEmpty());
    }

    // ---- E02 ----

    @Test
    public void reconcileKeepsDeclaredDropsRemovedAndMarksAdded() {
        List<String> bodies = ScriptDatabase.reconcileRequires(
                Arrays.asList("https://a/1.js", "https://a/2.js", "https://a/3.js"),
                Arrays.asList("ONE", "TWO", "THREE"),
                Arrays.asList("https://a/3.js", "https://a/new.js", "https://a/1.js"));
        assertEquals(Arrays.asList("THREE", null, "ONE"), bodies);
        assertEquals(Collections.singletonList("https://a/new.js"),
                ScriptDatabase.missing(Arrays.asList("https://a/3.js", "https://a/new.js", "https://a/1.js"), bodies));
    }

    @Test
    public void reconcileRemovingEveryRequireLeavesNoBodies() {
        assertTrue(ScriptDatabase.reconcileRequires(
                Collections.singletonList("https://a/1.js"), Collections.singletonList("ONE"),
                Collections.<String>emptyList()).isEmpty());
    }

    @Test
    public void reconcileDistrustsMisalignedStoredBodies() {
        // Duplicate() stores requires without bodies; a mismatch means every body is refetched.
        List<String> bodies = ScriptDatabase.reconcileRequires(
                Arrays.asList("https://a/1.js", "https://a/2.js"), Collections.singletonList("ONE"),
                Arrays.asList("https://a/1.js"));
        assertEquals(Collections.singletonList((String) null), bodies);
    }

    @Test
    public void reconcileHandlesARepeatedUrl() {
        List<String> bodies = ScriptDatabase.reconcileRequires(
                Collections.singletonList("https://a/1.js"), Collections.singletonList("ONE"),
                Arrays.asList("https://a/1.js", "https://a/1.js"));
        assertEquals(Arrays.asList("ONE", "ONE"), bodies);
    }

    // ---- E03 ----

    @Test
    public void exportWritesAHeaderThatImportAccepts() throws Exception {
        String text = ScriptDatabase.backupJson(Arrays.asList(script("a"), script("b")));
        JSONObject root = new JSONObject(text);
        assertEquals(ScriptDatabase.BACKUP_FORMAT, root.getString("format"));
        assertEquals(ScriptDatabase.BACKUP_VERSION, root.getInt("version"));
        assertTrue(ScriptDatabase.looksLikeBackup(text));
        assertEquals(Arrays.asList("a", "b"), ids(ScriptDatabase.parseBackup(text)));
    }

    @Test
    public void oneLimitForExportImportAndDatabase() throws Exception {
        Userscript big = script("big");
        char[] filler = new char[ScriptDatabase.MAX_BACKUP_BYTES / 2 + 1];
        Arrays.fill(filler, 'é'); // 2 UTF-8 bytes each: over the limit in bytes, not in chars
        big.source = new String(filler);
        try {
            ScriptDatabase.backupJson(Collections.singletonList(big));
            fail("export above the limit must be refused");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("larger than"));
        }
        ScriptDatabase db = new ScriptDatabase(folder.getRoot(), ScriptDatabase.REAL);
        db.write(Collections.singletonList(script("old")));
        try {
            db.write(Collections.singletonList(big));
            fail("database write above the limit must be refused");
        } catch (Exception expected) { }
        assertEquals(Collections.singletonList("old"), ids(db.load()));
        assertFalse(db.tmp().exists());
    }

    @Test
    public void acceptsTheLegacyFormatAndTheBareShape() throws Exception {
        String legacy = new JSONObject().put("format", ScriptDatabase.LEGACY_BACKUP_FORMAT)
                .put("version", 1).put("scripts", new JSONArray().put(script("x").toJson())).toString();
        assertEquals(Collections.singletonList("x"), ids(ScriptDatabase.parseBackup(legacy)));
        assertEquals(Collections.singletonList("y"), ids(ScriptDatabase.parseBackup(dbText("y"))));
    }

    @Test
    public void rejectsWrongHeadersAndShapes() throws Exception {
        String scripts = new JSONArray().put(script("x").toJson()).toString();
        assertRejected("{\"format\":\"SomethingElse\",\"version\":1,\"scripts\":" + scripts + "}");
        assertRejected("{\"format\":\"" + ScriptDatabase.BACKUP_FORMAT + "\",\"scripts\":" + scripts + "}");
        assertRejected("{\"format\":\"" + ScriptDatabase.BACKUP_FORMAT + "\",\"version\":2,\"scripts\":" + scripts + "}");
        assertRejected("{\"format\":\"" + ScriptDatabase.BACKUP_FORMAT + "\",\"version\":\"1\",\"scripts\":" + scripts + "}");
        // A script that merely mentions the marker is not a backup.
        assertRejected("{\"note\":\"BraveUserscripts-backup\",\"scripts\":" + scripts + "}");
        assertRejected("{\"format\":\"" + ScriptDatabase.BACKUP_FORMAT + "\",\"version\":1,\"scripts\":[{\"name\":\"no id\",\"source\":\"\"}]}");
        assertRejected("{\"format\":\"" + ScriptDatabase.BACKUP_FORMAT + "\",\"version\":1,\"scripts\":[42]}");
        assertRejected("{\"format\":\"" + ScriptDatabase.BACKUP_FORMAT + "\",\"version\":1}");
        assertRejected("{ broken");
    }

    @Test
    public void userscriptsAreNotBackups() {
        assertFalse(ScriptDatabase.looksLikeBackup("// ==UserScript==\n// @name BraveUserscripts-backup\n"));
        assertFalse(ScriptDatabase.looksLikeBackup(null));
        assertTrue(ScriptDatabase.looksLikeBackup("  { broken"));
    }

    private static void assertRejected(String text) {
        try {
            List<Userscript> parsed = ScriptDatabase.parseBackup(text);
            fail("accepted: " + text + " -> " + ids(parsed));
        } catch (Exception expected) {
            // rejected before anything is replaced
        }
    }
}
