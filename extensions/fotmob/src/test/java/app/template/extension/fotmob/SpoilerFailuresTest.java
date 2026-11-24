package app.template.extension.fotmob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Spoiler-free mode's failure handling: once a match is known to be marked, an error hides its
 * score; every error is logged once per call site.
 */
public class SpoilerFailuresTest {
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

    /** FotMob's Match.StatusOfMatch, by constant name. */
    enum Status {
        SecondHalf, Finished
    }

    /** A stand-in for FotMob's Match: only the status field the decision reads. */
    static final class FakeMatch {
        @SuppressWarnings("unused")
        Status StatusOfMatch;

        FakeMatch(Status status) {
            StatusOfMatch = status;
        }
    }

    private static final SpoilerFree.Flag THROWS = new SpoilerFree.Flag() {
        @Override
        public boolean get() {
            throw new IllegalStateException("settings broken");
        }
    };

    private static final SpoilerFree.Flag OFF = new SpoilerFree.Flag() {
        @Override
        public boolean get() {
            return false;
        }
    };

    @Test
    public void eachSiteIsLoggedOnce() {
        SpoilerFailures.report("a", SpoilerFree.LISTS, new RuntimeException());
        SpoilerFailures.report("a", SpoilerFree.LISTS, new RuntimeException());
        SpoilerFailures.report("b", SpoilerFree.LISTS, new RuntimeException());
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
    public void aMarkedMatchStaysHiddenWhenItsDecisionFails() {
        assertTrue(SpoilerFree.markedHides(new FakeMatch(Status.Finished), THROWS));
        assertTrue(SpoilerFree.markedHides(new FakeMatch(Status.Finished), THROWS));
        assertEquals(Collections.singletonList("hides.marked"), sites);
    }

    @Test
    public void theMarkedDecisionIsUnchangedOnSuccess() {
        assertFalse(SpoilerFree.markedHides(new FakeMatch(Status.Finished), OFF));
        assertTrue(SpoilerFree.markedHides(new FakeMatch(Status.SecondHalf), OFF));
        assertTrue(sites.isEmpty());
    }

    @Test
    public void anUnreadableStatusHidesAndIsLoggedOnce() {
        // No StatusOfMatch field (or a cached field of another class): null, which counts as live.
        assertNull(SpoilerFree.statusName(new Object()));
        assertTrue(SpoilerFree.markedHides(new Object(), OFF));
        assertEquals(Collections.singletonList("statusName"), sites);
    }

    @Test
    public void anUnreadableMatchShowsItsScoreAndIsLoggedOnce() {
        // No id/league fields: whether it is marked is unknown, so fail open.
        assertFalse(SpoilerFree.isMarkedMatch(new Object(), SpoilerFree.LISTS));
        assertFalse(SpoilerFree.isMarkedMatch(new FakeMatch(Status.SecondHalf), SpoilerFree.MATCH_PAGE));
        assertEquals(Collections.singletonList("hides.marks"), sites);
    }

    @Test
    public void unmarkedOrMissingMatchesStayStockOffDevice() {
        // Off device every switch is off: stock, and nothing to log.
        assertFalse(SpoilerFree.hidesScoreInLists(new FakeMatch(Status.SecondHalf)));
        assertFalse(SpoilerFree.blocksPush(null));
        assertFalse(SpoilerFree.isMarked("1", 2, 3));
        assertTrue(sites.isEmpty());
    }
}
