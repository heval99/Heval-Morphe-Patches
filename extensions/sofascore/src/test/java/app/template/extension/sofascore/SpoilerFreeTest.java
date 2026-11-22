package app.template.extension.sofascore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;

import org.junit.Test;

import app.template.extension.hevalcontrols.HevalSettings;

/** The pure parts of the Compose row mask: score field selection and the pass-through. */
public class SpoilerFreeTest {
    /** Four same-typed cells (name, game score, set score, score) plus unrelated fields. */
    @SuppressWarnings("unused")
    static final class Line {
        static String ignoredStatic = "x";
        int number = 1;
        Object other;
        String d_score;
        String a_name;
        String c_set;
        String b_game;
    }

    /** Three same-typed fields: not the expected shape. */
    @SuppressWarnings("unused")
    static final class Odd {
        String a;
        String b;
        String c;
    }

    @Test
    public void scoreFieldsAreTheLastThreeByName() {
        Field[] fields = SpoilerFree.scoreFieldsOf(Line.class);
        assertEquals(3, fields.length);
        assertEquals("b_game", fields[0].getName());
        assertEquals("c_set", fields[1].getName());
        assertEquals("d_score", fields[2].getName());
    }

    @Test
    public void unexpectedShapeSelectsNothing() {
        assertEquals(0, SpoilerFree.scoreFieldsOf(Odd.class).length);
    }

    @Test
    public void maskRowPassesTheStatusThroughWhenTheFeatureIsOff() {
        // Without an Android runtime the settings are not available, so the feature reads as off.
        assertFalse(HevalSettings.isEnabled(SpoilerFree.LISTS));
        Object status = new Object();
        Line home = new Line();
        home.d_score = "2";
        assertSame(status, SpoilerFree.maskRow(1, null, status, home, new Line()));
        assertEquals("2", home.d_score);
        assertEquals(null, SpoilerFree.maskRow(1, null, null, null, null));
    }
}
