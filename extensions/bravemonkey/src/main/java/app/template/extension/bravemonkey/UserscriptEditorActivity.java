/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Source editor, metadata inspector and URL rule tester. Not exported: opened only from
 * {@link UserscriptManagerActivity}.
 *
 * Brave changes: the Greasy Fork / Sleazy Fork "Publish" button and flow are removed; Brave wording.
 */
public final class UserscriptEditorActivity extends Activity {
    static final String EXTRA_ID = "script_id";
    private static final int SAVE_SCRIPT = 51;
    private static final String EXPORT_KEY = "editor";
    private Userscript script;
    private EditText source;
    private boolean saving;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra(EXTRA_ID);
        script = id == null ? null : MonkeyStore.get(this, id);
        if (script == null) {
            finish();
            return;
        }
        render();
        registerBack();
    }

    private void render() {
        String current = source == null ? script.source : source.getText().toString();
        MonkeyUi.window(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(MonkeyUi.bg(this));
        root.addView(MonkeyUi.label(this, "Userscript editor", 23, true, MonkeyUi.text(this)));
        root.addView(MonkeyUi.label(this, script.name + "  •  "
                        + (Userscript.KIND_CSS.equals(script.kind)
                        ? "CSS userstyle"
                        : "JavaScript userscript"),
                12, false, MonkeyUi.muted(this)));

        LinearLayout actions = new LinearLayout(this);
        add(actions, "Save", true, () -> save(false));
        add(actions, "Metadata", false, this::metadata);
        add(actions, "Test URL", false, this::testUrl);
        add(actions, "GM APIs", false, this::apis);
        add(actions, "Export", false, this::export);
        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(actions);
        root.addView(actionScroll, params(-1, -2, 0, 0));

        ScrollView scroll = new ScrollView(this);
        source = new EditText(this);
        source.setText(current);
        source.setTextColor(MonkeyUi.text(this));
        source.setHintTextColor(MonkeyUi.muted(this));
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        source.setTypeface(Typeface.MONOSPACE);
        source.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        source.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        source.setHorizontallyScrolling(true);
        source.setPadding(dp(12), dp(12), dp(12), dp(24));
        source.setBackground(MonkeyUi.card(this));
        scroll.addView(source, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        LinearLayout bottom = new LinearLayout(this);
        add(bottom, script.enabled ? "Disable" : "Enable", false, this::toggle);
        add(bottom, "Duplicate", false, this::duplicate);
        add(bottom, "Delete", false, this::delete);
        root.addView(bottom, params(-1, -2, 8, 0));
        setContentView(root);
    }

    // Off the UI thread: newly declared @require files are downloaded before anything is saved.
    private void save(boolean finishAfter) {
        if (saving) return;
        saving = true;
        Userscript request = script.copy();
        request.source = source.getText().toString();
        MonkeyStore.saveAsync(this, request, (ok, message, saved) -> runOnUiThread(() -> {
            saving = false;
            if (isFinishing() || isDestroyed()) return;
            toast(message);
            if (!ok) return;
            if (saved != null) script = saved;
            if (finishAfter) finish();
        }));
    }

    private void metadata() {
        Userscript parsed = UserscriptMetadataParser.reparsePreservingState(
                script,
                source.getText().toString()
        );
        String text = "Name: " + parsed.name
                + "\nVersion: " + parsed.version
                + "\nNamespace: " + parsed.namespace
                + "\nRun at: " + parsed.runAt
                + "\nKind: " + parsed.kind
                + "\n\n@match\n" + join(parsed.matches)
                + "\n\n@include\n" + join(parsed.includes)
                + "\n\n@exclude\n" + join(parsed.excludes)
                + "\n\n@exclude-match\n" + join(parsed.excludeMatches)
                + "\n\n@grant (not enforced)\n" + join(parsed.grants)
                + "\n\n@require\n" + join(parsed.requires);
        new AlertDialog.Builder(this)
                .setTitle("Parsed metadata")
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void testUrl() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/page");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Test URL rules")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Test", (dialog, which) -> {
                    Userscript parsed = UserscriptMetadataParser.reparsePreservingState(
                            script,
                            source.getText().toString()
                    );
                    parsed.enabled = true;
                    boolean matches = UrlPatternMatcher.matches(
                            parsed,
                            input.getText().toString().trim()
                    );
                    toast(matches
                            ? "This script matches the URL"
                            : "This script does not match the URL");
                }).show();
    }

    private void apis() {
        new AlertDialog.Builder(this)
                .setTitle("Brave Userscripts GM compatibility")
                .setMessage("Supported core APIs:\n\nGM_info / GM.info\nGM_getValue, GM_setValue, "
                        + "GM_deleteValue, GM_listValues\nPromise-style GM value APIs\nGM_addStyle\n"
                        + "GM_log\nGM_registerMenuCommand\nGM_openInTab\nGM_setClipboard\n"
                        + "GM_notification\nGM_download\nGM_xmlhttpRequest (page fetch / CORS applies)\n"
                        + "unsafeWindow (= window)\n\nScripts run in the page itself: @grant is not "
                        + "enforced and GM values are kept in the site's localStorage. "
                        + "@run-at document-start runs as soon as Brave reports the page loaded. "
                        + "@require files are downloaded (https only) on install and update. "
                        + "Changes apply on the next page load.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void export() {
        MonkeyStore.putPendingExport(EXPORT_KEY, source.getText().toString());
        String extension = Userscript.KIND_CSS.equals(script.kind)
                ? ".user.css"
                : ".user.js";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/plain")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, UserscriptManagerActivity.safeName(script.name) + extension);
        startActivityForResult(intent, SAVE_SCRIPT);
    }

    private void toggle() {
        try {
            boolean enabled = !script.enabled;
            MonkeyStore.setEnabled(this, script.id, enabled);
            script.enabled = enabled; // only after the store accepted it
            render();
        } catch (Exception error) {
            toast(MonkeyStore.safe(error));
        }
    }

    private void duplicate() {
        try {
            Userscript copy = UserscriptMetadataParser.parse(
                    source.getText().toString(),
                    script.name + " copy.user.js",
                    ""
            );
            copy.name = script.name + " copy";
            copy.id = UserscriptMetadataParser.stableId(
                    copy.namespace + ".copy." + System.currentTimeMillis(),
                    copy.name
            );
            copy.enabled = false;
            // Reuse the original's stored @require bodies; only new URLs are downloaded.
            copy.requires.clear();
            copy.requires.addAll(script.requires);
            copy.requireSources.clear();
            copy.requireSources.addAll(script.requireSources);
            MonkeyStore.saveAsync(this, copy, (ok, message, saved) -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                toast(ok ? "Duplicate created" : message);
            }));
        } catch (Exception error) {
            toast(MonkeyStore.safe(error));
        }
    }

    private void delete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + script.name + "?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    try {
                        MonkeyStore.remove(this, script.id);
                        finish();
                    } catch (Exception error) {
                        toast(MonkeyStore.safe(error));
                    }
                }).show();
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    }

    // API < 33 only; on 33+ the OnBackInvokedCallback from registerBack() handles back.
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (source == null || source.getText().toString().equals(script.source)) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Save changes?")
                .setNeutralButton("Discard", (dialog, which) -> finish())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Save", (dialog, which) -> save(true))
                .show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_SCRIPT) return;
        // Held in MonkeyStore, so it survives a configuration change while the picker is open.
        String content = MonkeyStore.takePendingExport(EXPORT_KEY);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (content == null) {
            toast("Export was interrupted; please export again");
            return;
        }
        Uri uri = data.getData();
        android.content.Context app = getApplicationContext();
        MonkeyStore.runInBackground(() -> {
            String message;
            try (OutputStream output = app.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new Exception("Cannot open the file");
                output.write(content.getBytes(StandardCharsets.UTF_8));
                message = "Script exported";
            } catch (Throwable error) {
                message = MonkeyStore.safe(error);
            }
            String shown = message;
            runOnUiThread(() -> MonkeyUi.toast(app, shown));
        });
    }

    private void add(LinearLayout row, String text, boolean primary, Runnable action) {
        TextView button = MonkeyUi.button(this, text, primary);
        button.setOnClickListener(view -> action.run());
        row.addView(button, params(-2, -2, 0, 7));
    }

    private LinearLayout.LayoutParams params(int width, int height, int top, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, dp(top), dp(right), 0);
        return params;
    }

    private int dp(int value) { return MonkeyUi.dp(this, value); }

    private void toast(String message) { MonkeyUi.toast(this, message); }

    private static String join(java.util.List<String> values) {
        return values.isEmpty() ? "(none)" : android.text.TextUtils.join("\n", values);
    }
}
