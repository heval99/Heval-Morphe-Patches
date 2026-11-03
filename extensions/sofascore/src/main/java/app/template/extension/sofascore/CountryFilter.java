package app.template.extension.sofascore;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.template.extension.hevalcontrols.HevalSettings;

/**
 * "Hide countries" (Patch settings list {@code sofascore.hidden_countries}): drops what belongs to
 * the picked countries from the leagues list, the date feed's country rows, the Live tab, the
 * chronological view and search.
 *
 * <p>The key of a Sofascore category is its {@code alpha2} code (Sofascore's own codes, e.g. EN
 * England, SX Scotland, WA Wales, NX Northern Ireland, XK Kosovo). Categories without one (World,
 * Europe, International, tennis tours, ...) are keyed {@code "slug:" + slug}, so they can be hidden
 * as their own entries. Favourites are not exempt: a hidden country wins.
 *
 * <p>Every hook returns its argument unchanged while nothing is picked, and on any failure (stock
 * behaviour, the feature shows as failed). The models are kotlinx-serialization classes whose names
 * and getters R8 keeps; they are read through reflection by getter name (the patch checks the
 * names exist). The hooks never change the app's objects: they build new lists and responses.
 */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public final class CountryFilter {
    static final String LIST_ID = "sofascore.hidden_countries";
    private static final String TAG = "Morphe-Sofascore";
    private static final String SLUG_PREFIX = "slug:";

    private static final String EVENT_LIST_RESPONSE = "com.sofascore.model.newNetwork.EventListResponse";

    private static final ConcurrentHashMap<String, Method> methods = new ConcurrentHashMap<>();
    private static volatile Constructor<?> eventListConstructor;
    private static volatile boolean seeded;

    private CountryFilter() {
    }

    // ---------------------------------------------------------------------------------------
    // Picker options
    // ---------------------------------------------------------------------------------------

    /**
     * The app's own country list, "code TAB name" lines. The patch rewrites this method to return
     * the list it read from the app at patch time; the source version is empty.
     */
    public static String seeds() {
        return "";
    }

    /** Patched in before every return of ReleaseApp.onCreate: offers the app's countries in the picker. */
    public static void init() {
        if (seeded) return;
        seeded = true;
        try {
            String encoded = seeds();
            if (encoded == null || encoded.isEmpty()) return;
            Map<String, String> options = new HashMap<>(512);
            for (String line : encoded.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab < line.length() - 1) options.put(line.substring(0, tab), line.substring(tab + 1));
            }
            HevalSettings.registerOptions(LIST_ID, options);
        } catch (Throwable failure) {
            Log.w(TAG, "Country seeds not registered: " + failure.getClass().getName());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Hooks
    // ---------------------------------------------------------------------------------------

    /**
     * SportCategoriesResponse.getCategories (List of Category): the leagues list of a sport. Reports
     * every category to the picker. Never empty: if every category would be hidden, the full list
     * stays.
     */
    public static List filterCategories(List categories) {
        if (categories == null || categories.isEmpty()) return categories;
        try {
            Set<String> hidden = HevalSettings.getHidden(LIST_ID);
            Object[] all = categories.toArray();
            List<Object> kept = new ArrayList<>(all.length);
            for (Object category : all) {
                if (!hiddenCategory(category, hidden)) kept.add(category);
            }
            if (kept.size() == all.length || kept.isEmpty()) return categories;
            return kept;
        } catch (Throwable failure) {
            fail(failure);
            return categories;
        }
    }

    /** CategoriesForDateResponse.getCategories (List of CategoryWrapper): the date feed's country rows. */
    public static List filterCategoryWrappers(List wrappers) {
        if (wrappers == null || wrappers.isEmpty()) return wrappers;
        try {
            Set<String> hidden = HevalSettings.getHidden(LIST_ID);
            Object[] all = wrappers.toArray();
            List<Object> kept = new ArrayList<>(all.length);
            for (Object wrapper : all) {
                Object category = wrapper == null ? null : call(wrapper, "getCategory");
                if (!hiddenCategory(category, hidden)) kept.add(wrapper);
            }
            return kept.size() == all.length ? wrappers : kept;
        } catch (Throwable failure) {
            fail(failure);
            return wrappers;
        }
    }

    /**
     * SearchResponse.getResults (List of SearchEntity): drops events, competitions, teams and
     * players of hidden countries. Managers, referees, venues, sports and stages stay.
     */
    public static List filterSearchResults(List results) {
        if (results == null || results.isEmpty()) return results;
        try {
            Set<String> hidden = HevalSettings.getHidden(LIST_ID);
            Object[] all = results.toArray();
            List<Object> kept = new ArrayList<>(all.length);
            for (Object result : all) {
                if (!hiddenCategory(searchCategory(result), hidden)) kept.add(result);
            }
            return kept.size() == all.length ? results : kept;
        } catch (Throwable failure) {
            fail(failure);
            return results;
        }
    }

    /**
     * The result of NetworkCoroutineAPI.sportLiveEvents (Live tab) and getIndexedEventsPage (the
     * chronological view), at the return of the request lambda. Anything that is not an
     * EventListResponse (the coroutine's suspend marker, null) passes through; otherwise a new
     * EventListResponse without the events of hidden countries is returned when any is hidden.
     */
    public static Object filterEventList(Object response) {
        if (response == null || !EVENT_LIST_RESPONSE.equals(response.getClass().getName())) return response;
        try {
            Object events = call(response, "getEvents");
            if (!(events instanceof List) || ((List) events).isEmpty()) return response;
            Set<String> hidden = HevalSettings.getHidden(LIST_ID);
            Object[] all = ((List) events).toArray();
            List<Object> kept = new ArrayList<>(all.length);
            for (Object event : all) {
                if (!hiddenCategory(eventCategory(event), hidden)) kept.add(event);
            }
            if (kept.size() == all.length) return response;
            Constructor<?> constructor = eventListConstructor;
            if (constructor == null || constructor.getDeclaringClass() != response.getClass()) {
                constructor = response.getClass().getConstructor(List.class);
                eventListConstructor = constructor;
            }
            return constructor.newInstance(kept);
        } catch (Throwable failure) {
            fail(failure);
            return response;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Keys
    // ---------------------------------------------------------------------------------------

    /** Reports {@code category} to the picker; true when its key is picked. */
    private static boolean hiddenCategory(Object category, Set<String> hidden) throws Exception {
        if (category == null) return false;
        String key = key(category);
        if (key == null) return false;
        Object name = call(category, "getName");
        HevalSettings.reportOptionsSeen(LIST_ID, key, name instanceof String ? (String) name : key);
        return hidden.contains(key);
    }

    /** The category's alpha2 code, else "slug:" + its slug, else null. */
    static String key(Object category) throws Exception {
        Object alpha2 = call(category, "getAlpha2");
        if (alpha2 instanceof String && !((String) alpha2).isEmpty()) return (String) alpha2;
        Object slug = call(category, "getSlug");
        if (slug instanceof String && !((String) slug).isEmpty()) return SLUG_PREFIX + slug;
        return null;
    }

    private static Object eventCategory(Object event) throws Exception {
        if (event == null) return null;
        Object tournament = call(event, "getTournament");
        return tournament == null ? null : call(tournament, "getCategory");
    }

    /** The category a search result belongs to, or null when it is not filtered. */
    private static Object searchCategory(Object result) throws Exception {
        if (result == null) return null;
        Object type = call(result, "getType");
        Object entity = call(result, "getEntity");
        if (!(type instanceof String) || entity == null) return null;
        switch ((String) type) {
            case "event":
                return eventCategory(entity);
            case "uniqueTournament":
            case "uniqueStage":
            case "team":
                return call(entity, "getCategory");
            case "player": {
                Object team = call(entity, "getTeam");
                return team == null ? null : call(team, "getCategory");
            }
            default:
                return null;
        }
    }

    /** {@code target.<name>()}, a public no-argument getter, cached per class. */
    private static Object call(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        String id = type.getName() + '#' + name;
        Method method = methods.get(id);
        if (method == null) {
            method = type.getMethod(name);
            methods.put(id, method);
        }
        return method.invoke(target);
    }

    private static void fail(Throwable failure) {
        HevalSettings.reportFailure(LIST_ID, failure);
    }
}
