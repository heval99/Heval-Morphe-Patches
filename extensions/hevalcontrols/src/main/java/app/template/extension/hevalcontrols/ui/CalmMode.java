package app.template.extension.hevalcontrols.ui;

import java.util.Map;

/**
 * The decision behind the "Calm mode" row, free of Android types so it runs in JVM unit tests
 * ({@code CalmModeTest}). Calm turns every calm switch on in one write, but only after the current
 * choices are durably saved as the profile {@value #BACKUP_NAME}: that profile is the only way back,
 * so Calm is never applied without it. When every calm switch is already on, nothing is saved or
 * written, so a repeated tap never replaces the earlier baseline with a state that is already calm.
 */
final class CalmMode {
    static final String BACKUP_NAME = "Before Calm mode";

    enum Outcome {
        /** The baseline was saved and the calm switches were turned on. */
        APPLIED,
        /** Every calm switch was already on: nothing was saved or changed. */
        ALREADY_CALM,
        /** No baseline profile and no free profile slot: nothing was changed. */
        STORE_FULL,
        /** The baseline profile could not be saved: nothing was changed. */
        BACKUP_FAILED,
        /** The baseline was saved but the calm switches could not be written. */
        APPLY_FAILED
    }

    /** The saved profiles ({@link Profiles}). */
    interface ProfileStore {
        String get(String name);

        int count();

        boolean save(String name, String text);
    }

    /** The current choices ({@code HevalSettings} and {@code SettingsTransfer}). */
    interface Settings {
        /** The saved choice of a switch, ignoring pause, safe mode and dependencies. */
        boolean isChosen(String featureId);

        /** The current choices as settings text, or null. */
        String export();

        boolean applyChoices(Map<String, Boolean> switches);
    }

    static Outcome activate(Map<String, Boolean> calm, ProfileStore profiles, Settings settings, int maxProfiles) {
        if (isActive(calm, settings)) return Outcome.ALREADY_CALM;
        String text = settings.export();
        if (text == null) return Outcome.BACKUP_FAILED;
        if (profiles.get(BACKUP_NAME) == null && profiles.count() >= maxProfiles) return Outcome.STORE_FULL;
        if (!profiles.save(BACKUP_NAME, text)) return Outcome.BACKUP_FAILED;
        return settings.applyChoices(calm) ? Outcome.APPLIED : Outcome.APPLY_FAILED;
    }

    /** Whether every calm switch is already on (by the saved choice). */
    static boolean isActive(Map<String, Boolean> calm, Settings settings) {
        if (calm == null || calm.isEmpty()) return false;
        for (String id : calm.keySet()) {
            if (!settings.isChosen(id)) return false;
        }
        return true;
    }

    private CalmMode() { }
}
