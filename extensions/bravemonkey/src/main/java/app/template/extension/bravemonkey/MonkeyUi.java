/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Contrast-safe styling for the programmatic Userscripts screens.
 *
 * Brave changes: the screens run on {@code @android:style/Theme.Material.NoActionBar} (a dark
 * theme), so the palette is dark. Theme attributes are looked up in the running package and then
 * in "android" (never a hard-coded browser package); no Brave resource is used by id. Anything
 * that does not resolve, or is not readable, falls back to neutral dark colours.
 */
final class MonkeyUi {
    private MonkeyUi() { }

    static boolean dark(Activity activity) {
        return true;
    }

    static int bg(Activity activity) {
        int fallback = 0xFF202124;
        int candidate = opaque(color(activity, fallback, "colorBackground"), fallback);
        return isLight(candidate) ? fallback : candidate;
    }

    static int surface(Activity activity) {
        int background = bg(activity);
        int candidate = 0xFF292A2D;
        if (contrastRatio(candidate, background) < 1.04) {
            candidate = blend(readableOn(background), background, 0.10f);
        }
        return candidate;
    }

    static int surfaceVariant(Activity activity) {
        int background = bg(activity);
        int candidate = 0xFF3C4043;
        if (contrastRatio(candidate, background) < 1.12) {
            candidate = blend(readableOn(background), background, 0.18f);
        }
        return candidate;
    }

    static int primary(Activity activity) {
        int fallback = systemAccent(activity, 0xFFA8C7FA, "system_accent1_200");
        int candidate = opaque(color(activity, fallback, "colorAccent"), fallback);
        if (contrastRatio(candidate, bg(activity)) < 3.0) candidate = fallback;
        if (contrastRatio(candidate, bg(activity)) < 3.0) candidate = 0xFFA8C7FA;
        return candidate;
    }

    static int secondary(Activity activity) {
        int fallback = systemAccent(activity, 0xFFC2E7FF, "system_accent2_200");
        if (contrastRatio(fallback, bg(activity)) < 3.0) fallback = 0xFFC2E7FF;
        return fallback;
    }

    static int onPrimary(Activity activity) {
        return readableOn(primary(activity));
    }

    static int text(Activity activity) {
        return readableOn(bg(activity));
    }

    static int muted(Activity activity) {
        int background = bg(activity);
        return blend(text(activity), background, 0.72f);
    }

    static int outline(Activity activity) {
        int background = surface(activity);
        return blend(text(activity), background, 0.34f);
    }

    static void window(Activity activity) {
        int background = bg(activity);
        View decor = activity.getWindow().getDecorView();
        activity.getWindow().setStatusBarColor(background);
        activity.getWindow().setNavigationBarColor(background);
        decor.setBackgroundColor(background);

        int flags = decor.getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);

        // Apply a sans-serif family after setContentView runs, keeping monospace source views.
        decor.post(() -> applySansTree(decor));
    }

    static Typeface typeface(boolean bold) {
        return Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    static void applyTypography(TextView view, boolean bold) {
        view.setTypeface(typeface(bold));
        view.setIncludeFontPadding(false);
    }

    static TextView button(Activity activity, String label, boolean filled) {
        TextView view = new TextView(activity);
        int background = filled ? primary(activity) : surfaceVariant(activity);
        int foreground = filled ? onPrimary(activity) : text(activity);
        if (contrastRatio(foreground, background) < 4.5) foreground = readableOn(background);

        view.setText(label);
        view.setTextColor(foreground);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        applyTypography(view, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(activity, 48));
        view.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
        view.setClickable(true);
        view.setFocusable(true);

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        if (!filled) shape.setStroke(dp(activity, 1), outline(activity));
        shape.setCornerRadius(dp(activity, 24));

        int ripple = withAlpha(foreground, 0x32);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, null));
        return view;
    }

    static TextView label(Activity activity, String text, int sp, boolean bold, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        applyTypography(view, bold);
        return view;
    }

    static GradientDrawable card(Activity activity) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(surface(activity));
        drawable.setCornerRadius(dp(activity, 20));
        drawable.setStroke(dp(activity, 1), outline(activity));
        return drawable;
    }

    static GradientDrawable input(Activity activity) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(surface(activity));
        drawable.setCornerRadius(dp(activity, 16));
        drawable.setStroke(dp(activity, 1), outline(activity));
        return drawable;
    }

    static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static void toast(Context context, String message) {
        try {
            Toast.makeText(context, message == null ? "Unknown error" : message, Toast.LENGTH_LONG).show();
        } catch (Throwable error) {
            Logger.e("toast failed", error);
        }
    }

    private static void applySansTree(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface current = text.getTypeface();
            if (!Typeface.MONOSPACE.equals(current)) {
                boolean bold = current != null && current.isBold();
                text.setTypeface(typeface(bold));
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            applySansTree(group.getChildAt(index));
        }
    }

    private static int color(Activity activity, int fallback, String... names) {
        try {
            for (String name : names) {
                int attr = activity.getResources().getIdentifier(name, "attr", activity.getPackageName());
                if (attr == 0) attr = activity.getResources().getIdentifier(name, "attr", "android");
                if (attr == 0) continue;

                TypedValue value = new TypedValue();
                if (!activity.getTheme().resolveAttribute(attr, value, true)) continue;
                try {
                    if (value.resourceId != 0) {
                        ColorStateList list = activity.getResources().getColorStateList(
                                value.resourceId, activity.getTheme());
                        return list.getColorForState(new int[]{android.R.attr.state_enabled},
                                list.getDefaultColor());
                    }
                } catch (Throwable ignored) { }
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data;
                }
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    private static int systemAccent(Activity activity, int fallback, String name) {
        if (Build.VERSION.SDK_INT < 31) return fallback;
        try {
            int id = activity.getResources().getIdentifier(name, "color", "android");
            if (id != 0) return activity.getColor(id);
        } catch (Throwable ignored) { }
        return fallback;
    }

    private static int opaque(int color, int fallback) {
        int alpha = Color.alpha(color);
        if (alpha < 0x40) return fallback;
        if (alpha == 0xFF) return color;
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int readableOn(int color) {
        return isLight(color) ? 0xFF202124 : Color.WHITE;
    }

    private static boolean isLight(int color) {
        return luminance(color) > 0.48;
    }

    private static double contrastRatio(int a, int b) {
        double one = luminance(a);
        double two = luminance(b);
        return (Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05);
    }

    private static double luminance(int color) {
        double r = channel(Color.red(color));
        double g = channel(Color.green(color));
        double b = channel(Color.blue(color));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int blend(int foreground, int background, float foregroundAmount) {
        float f = Math.max(0f, Math.min(1f, foregroundAmount));
        float b = 1f - f;
        return Color.rgb(
                Math.round(Color.red(foreground) * f + Color.red(background) * b),
                Math.round(Color.green(foreground) * f + Color.green(background) * b),
                Math.round(Color.blue(foreground) * f + Color.blue(background) * b)
        );
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
