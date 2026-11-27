package app.template.extension.brave;

import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import java.util.List;

/** Picks the fastest display mode of the current resolution for "Force max refresh rate". */
public final class DisplayHelper {
    private DisplayHelper() { }

    /**
     * Applies the highest refresh rate mode that keeps the active physical resolution. Returns true
     * when a mode was applied (or is already active) and false when the caller must run Chromium's
     * own refresh rate code. Never throws.
     */
    public static boolean applyMaxRefreshRate(Window window, List<?> supportedModes) {
        if (window == null || supportedModes == null || supportedModes.isEmpty()) return false;
        try {
            WindowManager.LayoutParams params = window.getAttributes();
            Display display = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    display = window.getContext().getDisplay();
                } catch (Throwable ignored) {
                    // fall back to the window manager
                }
            }
            if (display == null) display = window.getWindowManager().getDefaultDisplay();

            int width = 0;
            int height = 0;
            Display.Mode active = display != null ? display.getMode() : null;
            if (active != null) {
                width = active.getPhysicalWidth();
                height = active.getPhysicalHeight();
            }

            int best = 0;
            float bestRate = 0f;
            for (Object item : supportedModes) {
                if (!(item instanceof Display.Mode)) continue;
                Display.Mode mode = (Display.Mode) item;
                if (width > 0 && height > 0) {
                    int w = mode.getPhysicalWidth();
                    int h = mode.getPhysicalHeight();
                    if (!((w == width && h == height) || (w == height && h == width))) continue;
                }
                if (mode.getRefreshRate() > bestRate) {
                    bestRate = mode.getRefreshRate();
                    best = mode.getModeId();
                }
            }
            if (best == 0) return false;
            if (params.preferredDisplayModeId != best) {
                params.preferredDisplayModeId = best;
                window.setAttributes(params);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
