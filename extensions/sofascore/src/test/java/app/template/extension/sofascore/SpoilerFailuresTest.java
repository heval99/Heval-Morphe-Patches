package app.template.extension.sofascore;

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
 * Spoiler-free mode's failure handling: an error while deciding whether a match is marked shows the
 * score, an error once the match is known to be marked hides it, pushes are never dropped because of
 * an error, and every such error is logged once per call site.
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

    /** A map key whose lookup throws, standing in for a broken header or module. */
    static final class BadKey {
        @Override
        public int hashCode() {
            throw new IllegalStateException("broken");
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
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
        // Fail closed: the match is known to be marked, the keep-final read throws.
        assertTrue(SpoilerFree.markedHides(true, false, new SpoilerFreeTest.FakeEvent("finished"), THROWS));
        assertTrue(SpoilerFree.markedHides(false, true, new SpoilerFreeTest.FakeEvent("finished"), THROWS));
        assertEquals(Collections.singletonList("hidesScore.marked"), sites);
        // The success path is unchanged: a finished marked match shows its score again.
        assertFalse(SpoilerFree.markedHides(true, false, new SpoilerFreeTest.FakeEvent("finished"), OFF));
        assertTrue(SpoilerFree.markedHides(true, false, new SpoilerFreeTest.FakeEvent("inprogress"), OFF));
    }

    @Test
    public void anUnreadableMatchShowsItsScoreAndIsLoggedOnce() {
        // No getId(): whether it is marked is unknown, so fail open.
        assertFalse(SpoilerFree.hidesScore(new Object()));
        assertFalse(SpoilerFree.hidesScore(new Object()));
        assertEquals(Collections.singletonList("hidesScore.marks"), sites);
    }

    @Test
    public void anUnreadableStatusHidesAndIsLogged() {
        assertNull(SpoilerFree.statusType(new SpoilerFreeTest.FakeEvent(null)));
        assertTrue(SpoilerFree.markedHides(true, false, new SpoilerFreeTest.FakeEvent(null), OFF));
        assertEquals(Collections.singletonList("statusType"), sites);
    }

    @Test
    public void aBrokenPushIsNeverDroppedAndIsLoggedOnce() {
        SpoilerFree.Marks broken = new SpoilerFree.Marks() {
            @Override
            public boolean isMatchMarked(String eventId) {
                throw new IllegalStateException("marks broken");
            }
        };
        assertFalse(SpoilerFree.blocksPush("not json", broken, false));
        assertFalse(SpoilerFree.blocksPush("{\"icon\":\"goal\",\"open\":{\"target\":\"details\",\"id\":1}}", broken, true));
        assertEquals(Collections.singletonList("blocksPush.json"), sites);
    }

    @Test
    public void brokenMatchPageLookupsPassAndAreLoggedOnce() {
        BadKey key = new BadKey();
        assertFalse(SpoilerFree.hidesHeaderGoals(key));
        assertFalse(SpoilerFree.hidesHeaderGoals(key));
        assertEquals(4, SpoilerFree.moduleVisibility(key, 4));
        assertEquals(Arrays.asList("hidesHeaderGoals", "moduleVisibility"), sites);
    }
}
