package app.template.extension.shazam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RedirectMatcherTest {
    private static final String CB = "shazam-spotifyoauth://spotifylogincallback/free";

    @Test
    public void exactCallbackPasses() {
        assertTrue(RedirectMatcher.matches(CB, CB + "?code=abc", null));
        assertTrue(RedirectMatcher.matches(CB, CB + "#access_token=t&token_type=Bearer", null));
    }

    @Test
    public void schemeAndHostIgnoreCase() {
        assertTrue(RedirectMatcher.matches(CB, "SHAZAM-SpotifyOAuth://SpotifyLoginCallback/free?code=a", null));
    }

    @Test
    public void otherTargetsFail() {
        assertFalse(RedirectMatcher.matches(CB, "shazam-spotifyoauth://evil/free?code=a", null));
        assertFalse(RedirectMatcher.matches(CB, "shazam-spotifyoauth://spotifylogincallback/other", null));
        assertFalse(RedirectMatcher.matches(CB, "shazam-spotifyoauth://spotifylogincallback/free/extra", null));
        assertFalse(RedirectMatcher.matches(CB, "shazam-spotifyoauth://spotifylogincallback/Free", null));
        assertFalse(RedirectMatcher.matches(CB, "https://spotifylogincallback/free", null));
        assertFalse(RedirectMatcher.matches(CB, "shazam-spotifyoauth://spotifylogincallback:8080/free", null));
    }

    @Test
    public void userinfoFails() {
        assertFalse(RedirectMatcher.matches(CB, "shazam-spotifyoauth://user@spotifylogincallback/free", null));
        assertFalse(RedirectMatcher.matches(CB, "shazam-spotifyoauth://spotifylogincallback@evil/free", null));
    }

    @Test
    public void prefixOnlyMatchFails() {
        assertFalse(RedirectMatcher.matches(CB, CB + "evil", null));
        assertFalse(RedirectMatcher.matches(CB, CB + "evil?code=a", null));
    }

    @Test
    public void malformedAndNullFail() {
        assertFalse(RedirectMatcher.matches(CB, "not a uri", null));
        assertFalse(RedirectMatcher.matches(CB, null, null));
        assertFalse(RedirectMatcher.matches(null, CB, null));
        assertFalse(RedirectMatcher.matches(CB, "shazam-spotifyoauth:opaque", null));
    }

    @Test
    public void stateIsRequiredWhenSent() {
        assertTrue(RedirectMatcher.matches(CB, CB + "?code=a&state=s1", "s1"));
        assertTrue(RedirectMatcher.matches(CB, CB + "#access_token=t&state=s1", "s1"));
        assertFalse(RedirectMatcher.matches(CB, CB + "?code=a", "s1"));
        assertFalse(RedirectMatcher.matches(CB, CB + "?code=a&state=s2", "s1"));
        assertFalse(RedirectMatcher.matches(CB, CB + "?code=a&state=", "s1"));
        assertFalse(RedirectMatcher.matches(CB, CB + "?state=s1#state=s2", "s1"));
    }

    @Test
    public void stateIsDecoded() {
        assertTrue(RedirectMatcher.matches(CB, CB + "?state=a%20b%2Bc", "a b+c"));
    }
}
