package app.template.extension.flashscore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Spoiler-free mode's failure log (once per call site) and the push rule's stock answers. */
public class SpoilerFailuresTest {
    private final List<String> sites = new ArrayList<>();

    @Before
    public void listen() {
        SpoilerFailures.reset();
        SpoilerFailures.listener = new SpoilerFailures.Listener() {
            @Override
            public void onFailure(String site, String featureId, Throwable failure) {
                sites.add(site + "/" + featureId);
            }
        };
    }

    @After
    public void restore() {
        SpoilerFailures.reset();
    }

    @Test
    public void eachSiteIsLoggedOnce() {
        SpoilerFailures.report("blocksPush", SpoilerFree.NOTIFICATIONS, new RuntimeException());
        SpoilerFailures.report("blocksPush", SpoilerFree.NOTIFICATIONS, new RuntimeException());
        SpoilerFailures.report("other", SpoilerFree.LISTS, new RuntimeException());
        assertEquals(Arrays.asList("blocksPush/" + SpoilerFree.NOTIFICATIONS, "other/" + SpoilerFree.LISTS), sites);
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
    public void pushesPassOffDevice() {
        // Nothing is marked off device: every push passes and nothing failed.
        assertFalse(SpoilerFree.blocksPush(null));
        assertFalse(SpoilerFree.blocksPush(""));
        assertFalse(SpoilerFree.blocksPush("Q1xbZ0M2"));
        assertTrue(sites.isEmpty());
    }
}
