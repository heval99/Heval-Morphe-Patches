package app.template.extension.fotmob;

import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import app.template.extension.hevalcontrols.HevalSettings;

/**
 * Spoiler-free mode's failure log: a decision that throws is reported once per call site and
 * process through {@link HevalSettings#reportFailure} (the exception class and the first extension
 * frame only, never the message, and the feature shows as failed in Patch settings). Once per site,
 * so a broken hook on a hot path (list binds) does not flood the log. Never throws.
 */
final class SpoilerFailures {
    /** Test seam: told about the first failure of each site. */
    interface Listener {
        void onFailure(String site, String featureId, Throwable failure);
    }

    static volatile Listener listener;

    private static final String TAG = "Morphe-Spoiler";

    private static final Set<String> reported = new HashSet<>();

    private SpoilerFailures() {
    }

    /** Reports {@code failure} under {@code featureId} unless {@code site} (a constant tag) already reported one. */
    static void report(String site, String featureId, Throwable failure) {
        try {
            synchronized (reported) {
                if (!reported.add(site)) return;
            }
            Listener current = listener;
            if (current != null) current.onFailure(site, featureId, failure);
        } catch (Throwable ignored) {
            // Never let failure reporting fail a hook.
        }
        try {
            HevalSettings.reportFailure(featureId, failure);
        } catch (Throwable unavailable) {
            // The settings engine itself may be what broke: log the class name directly.
            try {
                Log.w(TAG, "Spoiler-free " + site + " failed: " + (failure == null ? "unknown" : failure.getClass().getName()));
            } catch (Throwable ignored) {
                // Never let failure reporting fail a hook.
            }
        }
    }

    /** Tests only: forgets the reported sites and the listener. */
    static void reset() {
        synchronized (reported) {
            reported.clear();
        }
        listener = null;
    }
}
