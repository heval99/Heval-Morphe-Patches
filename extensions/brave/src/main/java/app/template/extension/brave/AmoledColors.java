package app.template.extension.brave;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Pure black for Brave's dark surfaces ("AMOLED black theme"). Only fully opaque, dark and nearly
 * grey colors are changed, so accents, text, scrims and light themes keep their colors.
 */
public final class AmoledColors {
    /** Night mode of the color lookup in progress on this thread (a flag only, no Context is kept). */
    private static final ThreadLocal<Boolean> NIGHT = new ThreadLocal<>();

    private AmoledColors() { }

    /** Called when Brave starts resolving a theme color with the Context it resolves it for. */
    public static void enter(Context context) {
        boolean night = false;
        try {
            if (context != null) {
                int uiMode = context.getResources().getConfiguration().uiMode;
                night = (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            }
        } catch (Throwable ignored) {
            // treat as light
        }
        NIGHT.set(night);
    }

    /** Called with the color the lookup is about to return; returns the color to use. */
    public static int remap(int color) {
        try {
            return Boolean.TRUE.equals(NIGHT.get()) ? remapDark(color) : color;
        } catch (Throwable ignored) {
            return color;
        }
    }
    /** The dark theme mapping on its own (no Android classes, unit tested). */
    public static int remapDark(int color) {
        int alpha = color >>> 24;
        if (alpha != 0xFF) return color;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int spread = max - min;
        // Charcoal and blue-grey surface shades (#1F1F27, #1E2029, #121212, #202124 ...) become black
        if (max <= 0x2A && spread <= 16) return 0xFF000000;
        // Raised dark surfaces (menus, omnibox, cards: #2E3039, #303134, #383839 ...) become near black
        if (max <= 0x3B && spread <= 18) return 0xFF0C0C0E;
        return color;
    }
}
