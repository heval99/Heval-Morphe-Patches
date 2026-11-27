package app.template.extension.truecaller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NagPromosTest {
    @Test
    public void featureOnAlwaysHidesThePromo() {
        assertFalse(NagPromos.decide(true, true));
        assertFalse(NagPromos.decide(true, false));
    }

    @Test
    public void featureOffKeepsTheStockAnswer() {
        assertTrue(NagPromos.decide(false, true));
        assertFalse(NagPromos.decide(false, false));
    }
}
