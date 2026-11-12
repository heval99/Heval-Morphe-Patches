package app.template.extension.crexnav;

import android.view.View;
import android.view.ViewTreeObserver;

/** Keeps CREX's bottom navigation bar hidden. */
@SuppressWarnings("unused")
public final class CrexNav {

    private CrexNav() {
    }

    /**
     * Hides the bar and keeps it hidden if the app makes it visible again (for example when it
     * toggles the bar on scroll or after sign-in). Never throws into the app.
     */
    public static void hide(final View bar) {
        try {
            bar.setVisibility(View.GONE);
            bar.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
                    return true;
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
