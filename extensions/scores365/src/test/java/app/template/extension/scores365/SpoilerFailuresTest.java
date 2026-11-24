package app.template.extension.scores365;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.scores365.entitys.GameObj;

/**
 * Spoiler-free mode's failure handling: once a game is known to be marked, an error hides its
 * score; every error is logged once per call site.
 */
public class SpoilerFailuresTest {
    private static final String NBSP = String.valueOf((char) 0xA0);

    private final List<String> sites = new ArrayList<>();

    @Before
    public void listen() {
        SpoilerFailures.reset();
        SpoilerFailures.listener = new SpoilerFailures.Listener() {
            @Override
            public void onFailure(String site, String featureId, Throwable failure) {
                sites.add(site);
            }
        };
    }

    @After
    public void restore() {
        SpoilerFailures.reset();
    }

    @Test
    public void eachSiteIsLoggedOnce() {
        SpoilerFailures.report("a", SpoilerFree.FEATURE_MASTER, new RuntimeException());
        SpoilerFailures.report("a", SpoilerFree.FEATURE_MASTER, new RuntimeException());
        SpoilerFailures.report("b", SpoilerFree.FEATURE_MASTER, new RuntimeException());
        assertEquals(Arrays.asList("a", "b"), sites);
    }

    @Test
    public void reportingNeverThrows() {
        SpoilerFailures.listener = new SpoilerFailures.Listener() {
            @Override
            public void onFailure(String site, String featureId, Throwable failure) {
                throw new IllegalStateException("listener broken");
            }
        };
        SpoilerFailures.report("c", null, null);
    }

    @Test
    public void aMarkedGameStaysHiddenWhenItsStatusFails() {
        assertTrue(SpoilerFree.startedMarkedGame(new GameObj(true, false)));
        assertTrue(SpoilerFree.startedMarkedGame(new GameObj(true, false)));
        assertTrue(SpoilerFree.startedMarkedGame("not a game"));
        assertEquals(Collections.singletonList("hidesGame.marked"), sites);
    }

    @Test
    public void theMarkedDecisionIsUnchangedOnSuccess() {
        assertTrue(SpoilerFree.startedMarkedGame(new GameObj(false, false)));
        assertFalse(SpoilerFree.startedMarkedGame(new GameObj(false, true)));
        assertTrue(sites.isEmpty());
    }

    @Test
    public void aBrokenKickoffIsBlankNotTheScore() {
        // "" would make the row formatter show the stock score; a blank keeps it hidden.
        assertEquals(NBSP, SpoilerFree.kickoff("not a game"));
        assertEquals(NBSP, SpoilerFree.kickoff("not a game"));
        assertEquals(Collections.singletonList("kickoff"), sites);
    }

    @Test
    public void anUnreadableGameOrNotificationShowsTheScoreAndIsLoggedOnce() {
        SpoilerFree.useMarks(Collections.singleton("1"), Collections.<String>emptySet());
        try {
            // Not a GameObj / no Game field: whether it is marked is unknown, so fail open.
            assertFalse(SpoilerFree.hidesGame("not a game"));
            assertFalse(SpoilerFree.hidesGame("not a game"));
            assertFalse(SpoilerFree.notificationMarked(new Object()));
            assertFalse(SpoilerFree.notificationMarked(new Object()));
            assertEquals(Arrays.asList("hidesGame.marks", "blocksNotification"), sites);
            // A marked game (id 1) that has started is hidden; its status failing keeps it hidden.
            assertTrue(SpoilerFree.hidesGame(new GameObj(false, false)));
            assertTrue(SpoilerFree.hidesGame(new GameObj(true, false)));
            assertFalse(SpoilerFree.hidesGame(new GameObj(false, true)));
        } finally {
            SpoilerFree.useMarks(null, null);
        }
    }

    @Test
    public void unmarkedGamesStayStockOffDevice() {
        // Off device every switch is off and nothing is marked.
        assertEquals(null, SpoilerFree.rowScoreText(new GameObj(false, false)));
        assertFalse(SpoilerFree.blocksNotification(new Object()));
        assertTrue(sites.isEmpty());
    }
}
