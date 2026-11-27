package app.template.extension.brave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AmoledColorsTest {
    @Test
    public void darkSurfacesBecomeBlack() {
        assertEquals(0xFF000000, AmoledColors.remapDark(0xFF1E2029));
        assertEquals(0xFF000000, AmoledColors.remapDark(0xFF121212));
        assertEquals(0xFF000000, AmoledColors.remapDark(0xFF202124));
    }

    @Test
    public void raisedSurfacesBecomeNearBlack() {
        assertEquals(0xFF0C0C0E, AmoledColors.remapDark(0xFF2E3039));
        assertEquals(0xFF0C0C0E, AmoledColors.remapDark(0xFF303134));
    }

    @Test
    public void accentsLightColorsAndTransparentColorsStay() {
        assertEquals(0xFFFF4500, AmoledColors.remapDark(0xFFFF4500));
        assertEquals(0xFFFFFFFF, AmoledColors.remapDark(0xFFFFFFFF));
        assertEquals(0xFFE8EAED, AmoledColors.remapDark(0xFFE8EAED));
        assertEquals(0x801E2029, AmoledColors.remapDark(0x801E2029));
        assertEquals(0x00000000, AmoledColors.remapDark(0x00000000));
        assertEquals(0xFF2020FF, AmoledColors.remapDark(0xFF2020FF));
        assertTrue(AmoledColors.remapDark(0xFF5A5A5A) == 0xFF5A5A5A);
    }
}
