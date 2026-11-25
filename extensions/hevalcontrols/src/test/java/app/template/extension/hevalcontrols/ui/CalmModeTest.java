package app.template.extension.hevalcontrols.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class CalmModeTest {
    private static final int MAX = 20;

    /** An in-memory profile store with the same full-store rule as Profiles.save. */
    private static final class FakeProfiles implements CalmMode.ProfileStore {
        final Map<String, String> saved = new LinkedHashMap<>();
        boolean failSaves;

        @Override
        public String get(String name) {
            return saved.get(name);
        }

        @Override
        public int count() {
            return saved.size();
        }

        @Override
        public boolean save(String name, String text) {
            if (failSaves) return false;
            if (!saved.containsKey(name) && saved.size() >= MAX) return false;
            saved.put(name, text);
            return true;
        }
    }

    /** Switch choices; export() renders them so a test can see which state a profile holds. */
    private static class FakeSettings implements CalmMode.Settings {
        final Map<String, Boolean> chosen = new HashMap<>();
        boolean failApply;
        int applies;

        @Override
        public boolean isChosen(String featureId) {
            Boolean value = chosen.get(featureId);
            return value != null && value;
        }

        @Override
        public String export() {
            return "state:" + new java.util.TreeMap<>(chosen);
        }

        @Override
        public boolean applyChoices(Map<String, Boolean> switches) {
            applies++;
            if (failApply) return false;
            chosen.putAll(switches);
            return true;
        }
    }

    private static Map<String, Boolean> calm() {
        Map<String, Boolean> calm = new LinkedHashMap<>();
        calm.put("app.spoiler_free", Boolean.TRUE);
        calm.put("app.hide_betting", Boolean.TRUE);
        return calm;
    }

    @Test
    public void savesTheBaselineThenApplies() {
        FakeProfiles profiles = new FakeProfiles();
        FakeSettings settings = new FakeSettings();
        settings.chosen.put("app.other", Boolean.TRUE);
        String before = settings.export();

        assertEquals(CalmMode.Outcome.APPLIED, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals(before, profiles.get(CalmMode.BACKUP_NAME));
        assertTrue(settings.isChosen("app.spoiler_free"));
        assertTrue(settings.isChosen("app.hide_betting"));
    }

    @Test
    public void fullStoreDoesNotApplyCalm() {
        FakeProfiles profiles = new FakeProfiles();
        for (int i = 0; i < MAX; i++) profiles.saved.put("Profile " + i, "x");
        FakeSettings settings = new FakeSettings();

        assertEquals(CalmMode.Outcome.STORE_FULL, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals(0, settings.applies);
        assertFalse(settings.isChosen("app.spoiler_free"));
        assertNull(profiles.get(CalmMode.BACKUP_NAME));
    }

    @Test
    public void fullStoreWithAnExistingBaselineReplacesIt() {
        FakeProfiles profiles = new FakeProfiles();
        for (int i = 0; i < MAX - 1; i++) profiles.saved.put("Profile " + i, "x");
        profiles.saved.put(CalmMode.BACKUP_NAME, "old");
        FakeSettings settings = new FakeSettings();

        assertEquals(CalmMode.Outcome.APPLIED, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals("state:{}", profiles.get(CalmMode.BACKUP_NAME));
    }

    @Test
    public void failedBackupDoesNotApplyCalm() {
        FakeProfiles profiles = new FakeProfiles();
        profiles.failSaves = true;
        FakeSettings settings = new FakeSettings();

        assertEquals(CalmMode.Outcome.BACKUP_FAILED, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals(0, settings.applies);
        assertFalse(settings.isChosen("app.spoiler_free"));
    }

    @Test
    public void repeatedActivationKeepsTheOriginalBaseline() {
        FakeProfiles profiles = new FakeProfiles();
        FakeSettings settings = new FakeSettings();
        settings.chosen.put("app.other", Boolean.TRUE);
        String original = settings.export();

        assertEquals(CalmMode.Outcome.APPLIED, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals(CalmMode.Outcome.ALREADY_CALM, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals(original, profiles.get(CalmMode.BACKUP_NAME));
        assertEquals(1, settings.applies);
    }

    @Test
    public void alreadyCalmWithAFullStoreIsNotAnError() {
        FakeProfiles profiles = new FakeProfiles();
        for (int i = 0; i < MAX; i++) profiles.saved.put("Profile " + i, "x");
        FakeSettings settings = new FakeSettings();
        settings.chosen.putAll(calm());

        assertEquals(CalmMode.Outcome.ALREADY_CALM, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals(0, settings.applies);
    }

    @Test
    public void unreadableStateIsNotSavedOrApplied() {
        FakeProfiles profiles = new FakeProfiles();
        FakeSettings settings = new FakeSettings() {
            @Override
            public String export() {
                return null;
            }
        };

        assertEquals(CalmMode.Outcome.BACKUP_FAILED, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals(0, settings.applies);
        assertNull(profiles.get(CalmMode.BACKUP_NAME));
    }

    @Test
    public void failedWriteKeepsTheBaseline() {
        FakeProfiles profiles = new FakeProfiles();
        FakeSettings settings = new FakeSettings();
        settings.failApply = true;

        assertEquals(CalmMode.Outcome.APPLY_FAILED, CalmMode.activate(calm(), profiles, settings, MAX));
        assertEquals("state:{}", profiles.get(CalmMode.BACKUP_NAME));
    }
}
