package app.template.extension.truecaller;

import app.template.extension.hevalcontrols.HevalSettings;

/**
 * "Disable nags": the call-log promo cards for Missed Call Notifications and Ring silent (Do Not Disturb
 * access) are shown when a cooldown read says it has elapsed. While the feature is on the patched reads
 * answer false, so the card is skipped (issue #153).
 */
@SuppressWarnings("unused")
public final class NagPromos {
    static final String FEATURE = "truecaller.disable_nags";

    private NagPromos() {
    }

    /** Called with the result of a cooldown read; false while "Disable nags" is on. */
    public static boolean hideCooldownPromo(boolean cooldownElapsed) {
        try {
            return decide(HevalSettings.isEnabled(FEATURE), cooldownElapsed);
        } catch (Throwable failure) {
            return cooldownElapsed;
        }
    }

    static boolean decide(boolean featureOn, boolean cooldownElapsed) {
        return !featureOn && cooldownElapsed;
    }
}
