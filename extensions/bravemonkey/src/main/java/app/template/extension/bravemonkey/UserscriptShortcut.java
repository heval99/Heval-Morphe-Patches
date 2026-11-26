/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.Collections;

/**
 * The "Userscripts" long-press launcher shortcut to the manager (port of ChromeUserscriptShortcut).
 *
 * Brave changes: {@code addDynamicShortcuts} (which adds or updates our one id) instead of
 * {@code setDynamicShortcuts}, which would replace any dynamic shortcuts Brave publishes itself.
 */
final class UserscriptShortcut {
    private static final String SHORTCUT_ID = "bravemonkey-manager";

    private UserscriptShortcut() { }

    static void publish(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1 || context == null) return;
        try {
            ShortcutManager manager = context.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            Intent intent = new Intent(context, UserscriptManagerActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ShortcutInfo shortcut = new ShortcutInfo.Builder(context, SHORTCUT_ID)
                    .setShortLabel("Userscripts")
                    .setLongLabel("Open userscript manager")
                    .setIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_manage))
                    .setIntent(intent)
                    .build();
            manager.addDynamicShortcuts(Collections.singletonList(shortcut));
        } catch (Throwable error) {
            Logger.e("shortcut publish failed", error);
        }
    }
}
