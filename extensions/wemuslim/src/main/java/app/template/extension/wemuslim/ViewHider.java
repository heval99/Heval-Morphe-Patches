package app.template.extension.wemuslim;

import android.view.View;
import android.view.ViewGroup;

/**
 * Keeps a view GONE. Called from patched code where no free register can hold the constant 8
 * (the bottom bar's Messages red dot) or where the constructor no longer holds the view itself.
 */
public final class ViewHider {

    private ViewHider() {
    }

    public static void gone(View view) {
        if (view != null) view.setVisibility(View.GONE);
    }

    /**
     * Takes a view out of its parent for good: unlike setVisibility, the screen's later "show" calls
     * (the Me tab's login prompt card) then change nothing on screen.
     */
    public static void remove(View view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        view.setVisibility(View.GONE);
    }
}
