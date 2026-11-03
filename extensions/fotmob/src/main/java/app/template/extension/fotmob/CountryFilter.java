package app.template.extension.fotmob;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import app.template.extension.hevalcontrols.AppContext;
import app.template.extension.hevalcontrols.HevalSettings;

/**
 * "Hide countries" (Patch settings list {@code fotmob.hidden_countries}): drops the leagues of the
 * picked countries from the match lists, the leagues list and search. Keys are FotMob's league
 * country codes (FIFA three-letter codes; "INT" national-team competitions, "INT-2" club
 * competitions). Favourite leagues, and matches of favourite teams, always stay.
 *
 * <p>Every hook returns its argument unchanged while nothing is picked, and on any failure (stock
 * behaviour, the feature shows as failed). FotMob's models keep their field names (Gson and
 * kotlinx models; their methods are R8-renamed), so they are read by field name through
 * reflection. The hooks never change the app's objects: they build new lists and holders.
 */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public final class CountryFilter {
    static final String LIST_ID = "fotmob.hidden_countries";
    private static final String TAG = "Morphe-FotMob";

    private static final String LIVE_MATCHES = "com.fotmob.models.LiveMatches";
    private static final String LEAGUE_MATCHES = "com.fotmob.models.LeagueMatches";
    private static final String LEAGUE = "com.fotmob.models.League";
    private static final String MATCH = "com.fotmob.models.Match";
    private static final String TEAM = "com.fotmob.models.Team";
    private static final String SEARCH = "com.fotmob.models.search.";

    /** League.FAVORITE_LEAGUE_ID and the pseudo codes League uses for favourites and staff picks. */
    private static final int FAVOURITE_LEAGUE_ID = -99;

    private static volatile Context context;
    private static final ConcurrentHashMap<String, Field> fields = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Constructor<?>> constructors = new ConcurrentHashMap<>();

    private CountryFilter() {
    }

    /** From ActivityHooks.register (FotMobApp.onCreate): the picker's country list and flags. */
    public static void init(Application application) {
        try {
            if (application != null) context = application.getApplicationContext();
            HevalSettings.registerOptions(LIST_ID, CountrySeeds.NAMES);
            // The picker's flags (never saved, never used for filtering).
            HevalSettings.setOptionIconProvider(LIST_ID, CountryIcons.INSTANCE);
        } catch (Throwable failure) {
            Log.w(TAG, "Country seeds not registered: " + failure.getClass().getName());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Hook 1: match lists (LiveMatchesAndLeagueRank.<init>, the LiveMatches it stores)
    // ---------------------------------------------------------------------------------------

    /** A new LiveMatches without the hidden leagues; the cached original stays untouched. */
    public static Object filterLiveMatches(Object liveMatches) {
        if (liveMatches == null) return null;
        Set<String> hidden = HevalSettings.getHidden(LIST_ID);
        if (hidden.isEmpty()) return liveMatches;
        try {
            List leagues = (List) field(LIVE_MATCHES, "leagueMatches").get(liveMatches);
            if (leagues == null || leagues.isEmpty()) return liveMatches;
            Context app = context();
            CountryData.Favourites favourites = CountryData.favourites(app);
            List<Object> kept = new ArrayList<>(leagues.size());
            boolean changed = false;
            for (Object leagueMatches : leagues.toArray()) {
                Object result = filterLeagueMatches(app, leagueMatches, hidden, favourites);
                if (result != leagueMatches) changed = true;
                if (result != null) kept.add(result);
            }
            if (!changed) return liveMatches;
            return constructor(LIVE_MATCHES, List.class).newInstance(kept);
        } catch (Throwable failure) {
            fail(failure);
            return liveMatches;
        }
    }

    /** The LeagueMatches itself, null to drop it, or a copy with only the favourite teams' matches. */
    private static Object filterLeagueMatches(Context app, Object leagueMatches, Set<String> hidden,
                                              CountryData.Favourites favourites) throws Exception {
        if (leagueMatches == null) return null;
        Object league = field(LEAGUE_MATCHES, "league").get(leagueMatches);
        if (league == null) return leagueMatches;
        String code = (String) field(LEAGUE, "CountryCode").get(league);
        int id = field(LEAGUE, "Id").getInt(league);
        int parentId = field(LEAGUE, "ParentId").getInt(league);
        CountryData.learn(id, code);
        if (!isHidden(app, hidden, code, id, parentId) || favourites.league(id, parentId)) return leagueMatches;

        Object matches = field(LEAGUE_MATCHES, "Matches").get(leagueMatches);
        if (!(matches instanceof List)) return null;
        Object[] all = ((List) matches).toArray();
        List<Object> kept = new ArrayList<>();
        for (Object match : all) {
            if (match != null && (favourites.team(teamId(field(MATCH, "HomeTeam").get(match)))
                    || favourites.team(teamId(field(MATCH, "AwayTeam").get(match))))) {
                kept.add(match);
            }
        }
        if (kept.isEmpty()) return null;
        if (kept.size() == all.length) return leagueMatches;
        Object copy = constructor(LEAGUE_MATCHES, Collator.class).newInstance(Collator.getInstance());
        field(LEAGUE_MATCHES, "league").set(copy, league);
        field(LEAGUE_MATCHES, "sortPriority").setInt(copy, field(LEAGUE_MATCHES, "sortPriority").getInt(leagueMatches));
        field(LEAGUE_MATCHES, "Matches").set(copy, new Vector<>(kept));
        return copy;
    }

    private static int teamId(Object team) throws Exception {
        return team == null ? 0 : field(TEAM, "ID").getInt(team);
    }

    // ---------------------------------------------------------------------------------------
    // Hook 2: the leagues list (LeagueConverter.convert): the Leagues tab and onboarding
    // ---------------------------------------------------------------------------------------

    /**
     * Reports every country of the list to the picker and remembers each league's country, then
     * drops the hidden ones (favourites stay). If that would leave nothing, the full list stays, so
     * the Leagues tab is never empty.
     */
    public static List filterLeagues(List leagues) {
        if (leagues == null || leagues.isEmpty()) return leagues;
        try {
            Context app = context();
            Object[] all = leagues.toArray();
            Map<Integer, String> fresh = new HashMap<>(all.length * 2);
            Map<String, String> seen = new HashMap<>();
            for (Object league : all) {
                if (league == null) continue;
                String code = (String) field(LEAGUE, "CountryCode").get(league);
                // Staff picks are copies of leagues listed under their own country too, and carry the
                // picks group's name, not their country's.
                if (code == null || code.isEmpty() || isPseudoCode(code) || field(LEAGUE, "isSuggested").getBoolean(league)) continue;
                int id = field(LEAGUE, "Id").getInt(league);
                if (id > 0) fresh.put(id, code);
                if (!seen.containsKey(code)) {
                    String label = CountrySeeds.NAMES.get(code);
                    if (label == null) label = (String) field(LEAGUE, "CountryName").get(league);
                    seen.put(code, label);
                }
            }
            CountryData.remember(app, fresh);
            HevalSettings.reportOptionsSeen(LIST_ID, seen);

            Set<String> hidden = HevalSettings.getHidden(LIST_ID);
            if (hidden.isEmpty()) return leagues;
            CountryData.Favourites favourites = CountryData.favourites(app);
            List<Object> kept = new ArrayList<>(all.length);
            for (Object league : all) {
                if (league == null) continue;
                String code = (String) field(LEAGUE, "CountryCode").get(league);
                int id = field(LEAGUE, "Id").getInt(league);
                int parentId = field(LEAGUE, "ParentId").getInt(league);
                if (!isHidden(app, hidden, code, id, parentId) || favourites.league(id, parentId)) kept.add(league);
            }
            if (kept.size() == all.length || kept.isEmpty()) return leagues;
            return kept;
        } catch (Throwable failure) {
            fail(failure);
            return leagues;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Hook 3: search (the Suggestion constructors' leagueSuggestions/teamSuggestions/matchesSuggestions)
    // ---------------------------------------------------------------------------------------

    private interface Keep {
        boolean keep(Object item) throws Exception;
    }

    /** List<LeagueSuggestions>: drops league hits of hidden countries (their payload's countryCode). */
    public static List filterLeagueSuggestions(List groups) {
        final Set<String> hidden = HevalSettings.getHidden(LIST_ID);
        if (hidden.isEmpty() || groups == null || groups.isEmpty()) return groups;
        try {
            final Context app = context();
            final CountryData.Favourites favourites = CountryData.favourites(app);
            return filterGroups(groups, "LeagueSuggestions", "leagues", new Keep() {
                @Override
                public boolean keep(Object hit) throws Exception {
                    int id = idOf((String) field(SEARCH + "LeagueSuggestion", "nameAndId").get(hit));
                    Object payload = field(SEARCH + "LeagueSuggestion", "payload").get(hit);
                    String code = payload == null ? null : (String) field(SEARCH + "LeagueSuggestionPayload", "countryCode").get(payload);
                    CountryData.learn(id, code);
                    return !isHidden(app, hidden, code, id, 0) || favourites.league(id, 0);
                }
            });
        } catch (Throwable failure) {
            fail(failure);
            return groups;
        }
    }

    /** List<TeamSuggestions>: drops teams whose league belongs to a hidden country (favourites stay). */
    public static List filterTeamSuggestions(List groups) {
        final Set<String> hidden = HevalSettings.getHidden(LIST_ID);
        if (hidden.isEmpty() || groups == null || groups.isEmpty()) return groups;
        try {
            final Context app = context();
            final CountryData.Favourites favourites = CountryData.favourites(app);
            return filterGroups(groups, "TeamSuggestions", "teams", new Keep() {
                @Override
                public boolean keep(Object hit) throws Exception {
                    if (favourites.team(idOf((String) field(SEARCH + "TeamSuggestion", "nameAndId").get(hit)))) return true;
                    Object payload = field(SEARCH + "TeamSuggestion", "payload").get(hit);
                    if (payload == null) return true;
                    int league = CountryData.parseInt((String) field(SEARCH + "TeamSuggestionPayload", "leagueId").get(payload));
                    return !isLeagueIdHidden(app, hidden, league) || favourites.league(league, 0);
                }
            });
        } catch (Throwable failure) {
            fail(failure);
            return groups;
        }
    }

    /** List<MatchSuggestions>: drops matches of hidden countries' leagues unless a favourite team plays. */
    public static List filterMatchSuggestions(List groups) {
        final Set<String> hidden = HevalSettings.getHidden(LIST_ID);
        if (hidden.isEmpty() || groups == null || groups.isEmpty()) return groups;
        try {
            final Context app = context();
            final CountryData.Favourites favourites = CountryData.favourites(app);
            final String payloadClass = SEARCH + "MatchSuggestionPayload";
            return filterGroups(groups, "MatchSuggestions", "matches", new Keep() {
                @Override
                public boolean keep(Object hit) throws Exception {
                    Object payload = field(SEARCH + "MatchSuggestion", "payload").get(hit);
                    if (payload == null) return true;
                    int league = CountryData.parseInt((String) field(payloadClass, "leagueId").get(payload));
                    if (!isLeagueIdHidden(app, hidden, league) || favourites.league(league, 0)) return true;
                    return favourites.team(CountryData.parseInt((String) field(payloadClass, "homeTeamId").get(payload)))
                            || favourites.team(CountryData.parseInt((String) field(payloadClass, "awayTeamId").get(payload)));
                }
            });
        } catch (Throwable failure) {
            fail(failure);
            return groups;
        }
    }

    /** Rebuilds each group holder (constructor(List)) whose hit list lost an item. */
    private static List filterGroups(List groups, String holder, String listField, Keep keep) throws Exception {
        String holderClass = SEARCH + holder;
        Object[] all = groups.toArray();
        List<Object> result = new ArrayList<>(all.length);
        boolean changed = false;
        for (Object group : all) {
            List hits = group == null ? null : (List) field(holderClass, listField).get(group);
            if (hits == null || hits.isEmpty()) {
                result.add(group);
                continue;
            }
            Object[] items = hits.toArray();
            List<Object> kept = new ArrayList<>(items.length);
            for (Object item : items) {
                if (item == null || keep.keep(item)) kept.add(item);
            }
            if (kept.size() == items.length) {
                result.add(group);
            } else {
                changed = true;
                result.add(constructor(holderClass, List.class).newInstance(kept));
            }
        }
        return changed ? result : groups;
    }

    /** "Name|123" -> 123. */
    private static int idOf(String nameAndId) {
        if (nameAndId == null) return 0;
        int bar = nameAndId.lastIndexOf('|');
        return bar < 0 ? 0 : CountryData.parseInt(nameAndId.substring(bar + 1));
    }

    // ---------------------------------------------------------------------------------------
    // Decisions
    // ---------------------------------------------------------------------------------------

    /** The favourites group and the staff picks group are not countries. */
    static boolean isPseudoCode(String code) {
        return "AAA".equals(code) || "SELECTED".equals(code);
    }

    /**
     * Whether a league with this raw country code is hidden. A raw "INT" is resolved to "INT" or
     * "INT-2" by league id (then parent id); while that is unknown it is hidden only when both are.
     */
    private static boolean isHidden(Context app, Set<String> hidden, String code, int id, int parentId) {
        if (code == null || code.isEmpty() || id == FAVOURITE_LEAGUE_ID || isPseudoCode(code)) return false;
        if (!CountrySeeds.INTERNATIONAL.equalsIgnoreCase(code)) return hidden.contains(code);
        String resolved = international(CountryData.code(app, id));
        if (resolved == null) resolved = international(CountryData.code(app, parentId));
        if (resolved == null) {
            return hidden.contains(CountrySeeds.INTERNATIONAL) && hidden.contains(CountrySeeds.INTERNATIONAL_CLUBS);
        }
        return hidden.contains(resolved);
    }

    private static String international(String code) {
        return CountrySeeds.INTERNATIONAL.equals(code) || CountrySeeds.INTERNATIONAL_CLUBS.equals(code) ? code : null;
    }

    /** Search names only the league id: hidden when its known country is (unknown leagues stay). */
    private static boolean isLeagueIdHidden(Context app, Set<String> hidden, int league) {
        String code = CountryData.code(app, league);
        return code != null && !isPseudoCode(code) && hidden.contains(code);
    }

    // ---------------------------------------------------------------------------------------
    // Reflection and plumbing
    // ---------------------------------------------------------------------------------------

    private static Context context() {
        Context current = context;
        if (current == null) {
            current = AppContext.currentApplication();
            if (current != null) context = current;
        }
        return current;
    }

    private static Field field(String className, String name) throws Exception {
        String key = className + '#' + name;
        Field field = fields.get(key);
        if (field == null) {
            field = Class.forName(className, false, CountryFilter.class.getClassLoader()).getDeclaredField(name);
            field.setAccessible(true);
            fields.put(key, field);
        }
        return field;
    }

    private static Constructor<?> constructor(String className, Class<?> parameter) throws Exception {
        String key = className + '#' + parameter.getName();
        Constructor<?> constructor = constructors.get(key);
        if (constructor == null) {
            constructor = Class.forName(className, false, CountryFilter.class.getClassLoader()).getDeclaredConstructor(parameter);
            constructor.setAccessible(true);
            constructors.put(key, constructor);
        }
        return constructor;
    }

    private static void fail(Throwable failure) {
        HevalSettings.reportFailure(LIST_ID, failure);
    }
}
