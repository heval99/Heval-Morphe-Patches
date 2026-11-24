package app.template.extension.sofascore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.template.extension.hevalcontrols.HevalSettings;

/**
 * "Hide from pages" (Patch settings switch {@value #FEATURE_ID}, off by default, 2026-11-23): adds
 * the "Hide countries" and "Hide competitions" picks to the app itself, so the user does not have
 * to find the entry in Patch settings.
 *
 * <ul>
 *   <li>A competition's page (LeagueActivity) gets two overflow items: "Hide this competition"
 *       (needs "Hide competitions") and "Hide country: name" (needs "Hide countries"). Each asks for
 *       confirmation, saves the pick, and closes the page.</li>
 *   <li>A country row of the Leagues tab gets a long press that asks to hide that country (needs
 *       "Hide countries"). The tap still opens the row as before.</li>
 * </ul>
 *
 * <p>Each action is offered only while the switch is on and the list's patch is in
 * (CountryFilter.patched(), CompetitionFilter.patched()); the switch is read again on every tap,
 * and the confirmation dialog guards against a mistaken tap. The keys and labels are the lists' own
 * (CompetitionFilter.key and label, CountryFilter.key and the baked country name), so a pick made
 * here shows in the Patch settings pickers, where it can be undone. The lists are read when their
 * data loads, so a list already on screen updates at its next refresh or after a restart.
 */
@SuppressWarnings("unused")
public final class QuickHide {
    static final String FEATURE_ID = "sofascore.quick_hide";
    private static final String TAG = "Morphe-Sofascore";

    /** Outside Sofascore's own 0x7f...... ids and next to Spoiler-free's 0x7e5f0001. */
    static final int COMPETITION_ITEM_ID = 0x7e5f0002;
    static final int COUNTRY_ITEM_ID = 0x7e5f0003;
    /** After the inflated items and Spoiler-free's item (order 1000), inside the overflow menu. */
    private static final int ITEM_ORDER = 1001;

    static final String COMPETITION_TITLE = "Hide this competition";
    static final String DONE_TOAST = "Hidden. Refresh or restart to update lists";
    static final String FAILED_TOAST = "Could not save the change";

    private QuickHide() {
    }

    // ---------------------------------------------------------------------------------------
    // Pure rules (unit-tested)
    // ---------------------------------------------------------------------------------------

    /** Whether an action is offered: the switch is on, its list's patch is in, and the subject has a key not yet hidden. */
    static boolean offers(boolean switchOn, boolean listPatched, String key, boolean alreadyHidden) {
        return switchOn && listPatched && key != null && !alreadyHidden;
    }

    /** The country item's title: "Hide country: name", or "Hide this country" without a name. */
    static String countryTitle(String name) {
        String clean = name == null ? "" : name.trim();
        return clean.isEmpty() ? "Hide this country" : "Hide country: " + clean;
    }

    /** The confirmation text for {@code name}. */
    static String confirmText(String name) {
        String clean = name == null ? "" : name.trim();
        return "Hide " + (clean.isEmpty() ? "this" : clean) + "? You can undo it in Patch settings.";
    }

    /** A country's picker label: the baked country name, else the category's own name, else the key. */
    static String countryLabel(String key, String seedName, String categoryName) {
        if (seedName != null && !seedName.trim().isEmpty()) return seedName.trim();
        if (categoryName != null && !categoryName.trim().isEmpty()) return categoryName.trim();
        return key;
    }

    /** A competition's picker label: "category · name" like "Hide competitions", else "Competition key". */
    static String competitionLabel(String key, String categoryName, String name) {
        String label = CompetitionFilter.label(categoryName, name);
        return label != null ? label : "Competition " + key;
    }

    // ---------------------------------------------------------------------------------------
    // Subjects
    // ---------------------------------------------------------------------------------------

    /** One action: which list, the key, the picker label and the name the dialog shows. */
    static final class Target {
        final String listId;
        final String key;
        final String label;
        final String name;

        Target(String listId, String key, String label, String name) {
            this.listId = listId;
            this.key = key;
            this.label = label;
            this.name = name;
        }
    }

    private static boolean switchOn() {
        return HevalSettings.isEnabled(FEATURE_ID);
    }

    private static String text(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
    }

    /** The country target of Sofascore's Category {@code category}, or null when it is not offered right now. */
    static Target countryTarget(Object category) throws Exception {
        if (category == null || !CountryFilter.patched() || !switchOn()) return null;
        String key = CountryFilter.key(category);
        if (key == null || HevalSettings.isHidden(CountryFilter.LIST_ID, key)) return null;
        String categoryName = text(CountryFilter.call(category, "getName"));
        String label = countryLabel(key, CountryFilter.seedLabel(key), categoryName);
        return new Target(CountryFilter.LIST_ID, key, label, label);
    }

    /** The competition target of UniqueTournament {@code competition}, or null when it is not offered right now. */
    static Target competitionTarget(Object competition) throws Exception {
        if (competition == null || !CompetitionFilter.patched() || !switchOn()) return null;
        String key = CompetitionFilter.key(CountryFilter.call(competition, "getId"));
        if (key == null || HevalSettings.isHidden(CompetitionFilter.LIST_ID, key)) return null;
        Object category = CountryFilter.call(competition, "getCategory");
        String categoryName = category == null ? null : text(CountryFilter.call(category, "getName"));
        String name = text(CountryFilter.call(competition, "getName"));
        String label = competitionLabel(key, categoryName, name);
        return new Target(CompetitionFilter.LIST_ID, key, label, name != null ? name : label);
    }

    // ---------------------------------------------------------------------------------------
    // Competition page (LeagueActivity)
    // ---------------------------------------------------------------------------------------

    /**
     * The items and the competition of each open competition page. The items are held weakly: a
     * MenuItem reaches its menu and so its Activity, and a value that strongly reaches its own
     * WeakHashMap key never lets the entry clear (one leaked page per competition opened). The
     * menu holds its items for as long as it is shown, so a cleared item is one no longer on screen.
     */
    static final class Page {
        private WeakReference<MenuItem> competitionItem;
        private WeakReference<MenuItem> countryItem;
        Object competition;

        void setItems(MenuItem competition, MenuItem country) {
            competitionItem = competition == null ? null : new WeakReference<>(competition);
            countryItem = country == null ? null : new WeakReference<>(country);
        }

        MenuItem competitionItem() {
            return competitionItem == null ? null : competitionItem.get();
        }

        MenuItem countryItem() {
            return countryItem == null ? null : countryItem.get();
        }
    }

    /** Weakly keyed by the page's Activity (Object so the unit test can key it without android.jar). */
    private static final Map<Object, Page> pages = new WeakHashMap<>();

    private static Page page(Object host) {
        return host instanceof Activity ? pageFor(host) : null;
    }

    /** The page state of {@code key}, created on first use. */
    static Page pageFor(Object key) {
        Page page = pages.get(key);
        if (page == null) {
            page = new Page();
            pages.put(key, page);
        }
        return page;
    }

    /**
     * Patched into LeagueActivity.onCreateOptionsMenu, behind {@value #FEATURE_ID}: adds the two
     * items, hidden until the competition is known (see {@link #onLeague}).
     */
    public static void addLeagueItems(Object host, Menu menu) {
        try {
            final Page page = page(host);
            if (page == null || menu == null) return;
            // Held weakly by the handlers too, so nothing here outlives the page.
            final WeakReference<Activity> pageActivity = new WeakReference<>((Activity) host);
            menu.removeItem(COMPETITION_ITEM_ID);
            menu.removeItem(COUNTRY_ITEM_ID);
            MenuItem competitionItem = addItem(menu, COMPETITION_ITEM_ID, COMPETITION_TITLE, new Runnable() {
                @Override
                public void run() {
                    try {
                        Activity activity = pageActivity.get();
                        if (activity != null) confirm(activity, competitionTarget(page.competition), true);
                    } catch (Throwable failure) {
                        Log.w(TAG, "Hide competition failed: " + failure.getClass().getName());
                    }
                }
            });
            MenuItem countryItem = addItem(menu, COUNTRY_ITEM_ID, countryTitle(null), new Runnable() {
                @Override
                public void run() {
                    try {
                        Activity activity = pageActivity.get();
                        if (activity != null) confirm(activity, countryTarget(category(page.competition)), true);
                    } catch (Throwable failure) {
                        Log.w(TAG, "Hide country failed: " + failure.getClass().getName());
                    }
                }
            });
            page.setItems(competitionItem, countryItem);
            refresh(page);
        } catch (Throwable failure) {
            Log.w(TAG, "Hide items not added: " + failure.getClass().getName());
        }
    }

    /** Patched into LeagueActivity's only (UniqueTournament)V method (onPrepareOptionsMenu calls it), behind {@value #FEATURE_ID}. */
    public static void onLeague(Object host, Object competition) {
        try {
            Page page = page(host);
            if (page == null || competition == null) return;
            page.competition = competition;
            refresh(page);
        } catch (Throwable failure) {
            Log.w(TAG, "Hide items not updated: " + failure.getClass().getName());
        }
    }

    private static MenuItem addItem(Menu menu, int id, String title, final Runnable action) {
        MenuItem item = menu.add(Menu.NONE, id, ITEM_ORDER, title);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        item.setVisible(false);
        item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem clicked) {
                action.run();
                return true;
            }
        });
        return item;
    }

    private static Object category(Object competition) throws Exception {
        return competition == null ? null : CountryFilter.call(competition, "getCategory");
    }

    /** Shows each item only while its action is offered for the page's competition. */
    private static void refresh(Page page) throws Exception {
        Object competition = page.competition;
        MenuItem competitionItem = page.competitionItem();
        if (competitionItem != null) competitionItem.setVisible(competitionTarget(competition) != null);
        MenuItem countryItem = page.countryItem();
        if (countryItem != null) {
            Target country = countryTarget(category(competition));
            countryItem.setVisible(country != null);
            if (country != null) countryItem.setTitle(countryTitle(country.name));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Leagues tab country rows
    // ---------------------------------------------------------------------------------------

    /** The views this class gave a long press, so a row that stops offering it gets its stock state back. */
    private static final Map<View, Boolean> longPressViews = new WeakHashMap<>();

    /**
     * Patched into the start of the Leagues tab's country row binder with the row's view and the
     * row's Category (null for the other rows that holder binds). While a country can be hidden
     * from here, a long press on the row (the view the app gives its tap: card_content inside the
     * row, else the row itself) asks to hide it; otherwise a long press this class set is removed.
     * Called on every bind, so a recycled row or a changed switch is picked up.
     */
    public static void onCountryRow(View row, Object category) {
        try {
            if (row == null) return;
            View target = tapTarget(row);
            final Target country = countryTarget(category);
            if (country == null) {
                if (longPressViews.remove(target) != null) {
                    target.setOnLongClickListener(null);
                    target.setLongClickable(false);
                }
                return;
            }
            final Object rowCategory = category;
            target.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    try {
                        // Read again: the switch or the list may have changed since the bind.
                        Target now = countryTarget(rowCategory);
                        if (now == null) return false;
                        confirm(activity(view.getContext()), now, false);
                        return true;
                    } catch (Throwable failure) {
                        Log.w(TAG, "Hide country failed: " + failure.getClass().getName());
                        return false;
                    }
                }
            });
            longPressViews.put(target, Boolean.TRUE);
        } catch (Throwable failure) {
            HevalSettings.reportFailure(FEATURE_ID, failure);
        }
    }

    private static int cardContentId;

    /** The row's card_content view (the app's tap target when the row has one), else the row. */
    private static View tapTarget(View row) {
        if (cardContentId == 0) {
            Context context = row.getContext();
            cardContentId = context.getResources().getIdentifier("card_content", "id", context.getPackageName());
            if (cardContentId == 0) cardContentId = -1;
        }
        View card = cardContentId > 0 ? row.findViewById(cardContentId) : null;
        return card != null ? card : row;
    }

    // ---------------------------------------------------------------------------------------
    // Confirm and save
    // ---------------------------------------------------------------------------------------

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Asks, then hides {@code target}; {@code closePage} finishes the activity afterwards. */
    private static void confirm(final Activity activity, final Target target, final boolean closePage) {
        if (activity == null || target == null || activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
            .setMessage(confirmText(target.name))
            .setPositiveButton("Hide", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    hide(activity, target, closePage);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void hide(Activity activity, Target target, boolean closePage) {
        try {
            // setHidden is false for a list that is not registered or already full (MAX_HIDDEN).
            if (!HevalSettings.setHidden(target.listId, target.key, true)) {
                toast(activity, FAILED_TOAST);
                return;
            }
            HevalSettings.reportOptionsSeen(target.listId, target.key, target.label);
            toast(activity, DONE_TOAST);
            if (closePage) activity.finish();
        } catch (Throwable failure) {
            Log.w(TAG, "Hide failed: " + failure.getClass().getName());
            toast(activity, FAILED_TOAST);
        }
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // A toast is only feedback.
        }
    }
}
