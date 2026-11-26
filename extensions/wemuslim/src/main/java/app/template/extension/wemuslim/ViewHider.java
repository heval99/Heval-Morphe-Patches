package app.template.extension.wemuslim;

import android.view.View;

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
}
