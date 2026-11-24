package app.template.extension.sofascore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.MenuItem;

import org.junit.Test;

import java.lang.ref.WeakReference;

/** "Hide from pages": when an action is offered, its keys, labels and texts. */
public class QuickHideTest {
    /** A stand-in for Sofascore's Category (CountryFilter reads it by getter name). */
    public static final class FakeCategory {
        private final String alpha2;
        private final String slug;
        private final String name;

        FakeCategory(String alpha2, String slug, String name) {
            this.alpha2 = alpha2;
            this.slug = slug;
            this.name = name;
        }

        public String getAlpha2() {
            return alpha2;
        }

        public String getSlug() {
            return slug;
        }

        public String getName() {
            return name;
        }
    }

    @Test
    public void offeredOnlyWhenOnPatchedAndNotHiddenYet() {
        assertTrue(QuickHide.offers(true, true, "EN", false));
        assertFalse(QuickHide.offers(false, true, "EN", false));   // the switch is off (the default)
        assertFalse(QuickHide.offers(true, false, "EN", false));   // the list's patch is not in
        assertFalse(QuickHide.offers(true, true, null, false));    // the subject has no key
        assertFalse(QuickHide.offers(true, true, "EN", true));     // already hidden
    }

    @Test
    public void keysAreTheListsOwn() throws Exception {
        // Countries: alpha2, else "slug:" + slug (World, Europe, tours), like "Hide countries".
        assertEquals("EN", CountryFilter.key(new FakeCategory("EN", "england", "England")));
        assertEquals("slug:world", CountryFilter.key(new FakeCategory(null, "world", "World")));
        assertEquals("slug:europe", CountryFilter.key(new FakeCategory("", "europe", "Europe")));
        assertNull(CountryFilter.key(new FakeCategory(null, null, "Nowhere")));
        // Competitions: the UniqueTournament id, like "Hide competitions".
        assertEquals("17", CompetitionFilter.key(17));
        assertNull(CompetitionFilter.key(0));
    }

    @Test
    public void labelsMatchThePickers() {
        assertEquals("England", QuickHide.countryLabel("EN", "England", "England Amateur"));
        assertEquals("World", QuickHide.countryLabel("slug:world", null, " World "));
        assertEquals("slug:x", QuickHide.countryLabel("slug:x", " ", null));
        assertEquals("England · Premier League", QuickHide.competitionLabel("17", "England", "Premier League"));
        assertEquals("Premier League", QuickHide.competitionLabel("17", null, "Premier League"));
        assertEquals("Competition 17", QuickHide.competitionLabel("17", "England", null));
    }

    @Test
    public void textsArePlain() {
        assertEquals("Hide country: England", QuickHide.countryTitle("England"));
        assertEquals("Hide this country", QuickHide.countryTitle("  "));
        assertEquals("Hide this country", QuickHide.countryTitle(null));
        assertEquals("Hide Premier League? You can undo it in Patch settings.", QuickHide.confirmText("Premier League"));
        assertEquals("Hide this? You can undo it in Patch settings.", QuickHide.confirmText(null));
        assertEquals("Hide this competition", QuickHide.COMPETITION_TITLE);
    }

    @Test
    public void itemsDoNotClashWithSpoilerFree() {
        assertNotEquals(QuickHide.COMPETITION_ITEM_ID, QuickHide.COUNTRY_ITEM_ID);
        for (int id : new int[]{QuickHide.COMPETITION_ITEM_ID, QuickHide.COUNTRY_ITEM_ID}) {
            assertNotEquals(0x7e5f0001, id);               // Spoiler-free's item
            assertEquals(0x7e000000, id & 0xff000000);     // outside Sofascore's 0x7f ids
        }
        assertEquals("sofascore.quick_hide", QuickHide.FEATURE_ID);
    }

    /** Stores a page whose items reach their page (as a real MenuItem does); returns only a weak handle. */
    private static WeakReference<Object> storePage() {
        Object activity = new Object();
        QuickHide.Page page = QuickHide.pageFor(activity);
        page.setItems(PageLeakSupport.itemReaching(activity), PageLeakSupport.itemReaching(activity));
        page.competition = new Object();
        return new WeakReference<>(activity);
    }

    @Test
    public void pageStateDoesNotKeepItsActivity() throws Exception {
        assertTrue("a closed competition page stays reachable", PageLeakSupport.collected(storePage()));
    }

    @Test
    public void pageStateKeepsItsItemsWhileShown() {
        Object activity = new Object();
        MenuItem competition = PageLeakSupport.itemReaching(activity);
        MenuItem country = PageLeakSupport.itemReaching(activity);
        QuickHide.Page page = QuickHide.pageFor(activity);
        page.setItems(competition, country);
        assertSame(page, QuickHide.pageFor(activity));
        assertSame(competition, page.competitionItem());
        assertSame(country, page.countryItem());
    }

    @Test
    public void unpatchedSourceOffersNothing() throws Exception {
        // The list patches rewrite patched() to true; the source answers false, so nothing is offered.
        assertNull(QuickHide.countryTarget(new FakeCategory("EN", "england", "England")));
        assertNull(QuickHide.countryTarget(null));
        assertNull(QuickHide.competitionTarget(null));
        assertNull(QuickHide.competitionTarget(new Object()));
    }
}
