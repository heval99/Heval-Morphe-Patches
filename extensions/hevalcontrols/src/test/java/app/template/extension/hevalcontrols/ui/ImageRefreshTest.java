package app.template.extension.hevalcontrols.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.SocketTimeoutException;
import java.net.URL;

public class ImageRefreshTest {
    @Test
    public void throwingRefreshKeepsTheStaleCache() {
        String result = ImageRefresh.refreshOrStale("cached", new ImageRefresh.Fetch<String>() {
            @Override
            public String fetch() throws Exception {
                throw new SocketTimeoutException("timeout");
            }
        });
        assertEquals("cached", result);
    }

    @Test
    public void errorDuringRefreshKeepsTheStaleCache() {
        String result = ImageRefresh.refreshOrStale("cached", new ImageRefresh.Fetch<String>() {
            @Override
            public String fetch() {
                throw new OutOfMemoryError("decode");
            }
        });
        assertEquals("cached", result);
    }

    @Test
    public void emptyRefreshKeepsTheStaleCache() {
        String result = ImageRefresh.refreshOrStale("cached", new ImageRefresh.Fetch<String>() {
            @Override
            public String fetch() {
                return null;
            }
        });
        assertEquals("cached", result);
    }

    @Test
    public void freshResultWins() {
        String result = ImageRefresh.refreshOrStale("cached", new ImageRefresh.Fetch<String>() {
            @Override
            public String fetch() {
                return "fresh";
            }
        });
        assertEquals("fresh", result);
    }

    @Test
    public void failureWithoutCacheGivesNull() {
        assertNull(ImageRefresh.refreshOrStale(null, new ImageRefresh.Fetch<String>() {
            @Override
            public String fetch() throws Exception {
                throw new java.io.IOException("offline");
            }
        }));
    }

    @Test
    public void redirectToHttpIsRefusedBeforeItIsRequested() throws Exception {
        URL from = new URL("https://img.example.com/a.png");
        assertNull(ImageRefresh.redirectTarget(from, "http://img.example.com/a.png"));
        assertNull(ImageRefresh.redirectTarget(from, "ftp://img.example.com/a.png"));
        assertNull(ImageRefresh.redirectTarget(from, null));
        assertNull(ImageRefresh.redirectTarget(from, ""));
        assertNull(ImageRefresh.redirectTarget(from, "notaproto://x"));
    }

    @Test
    public void redirectToHttpsIsFollowedAndRelativeLocationsResolve() throws Exception {
        URL from = new URL("https://img.example.com/api/a.png");
        assertEquals("https://cdn.example.com/b.png", ImageRefresh.redirectTarget(from, "https://cdn.example.com/b.png").toString());
        assertEquals("https://img.example.com/api/c.png", ImageRefresh.redirectTarget(from, "c.png").toString());
        assertEquals("https://img.example.com/d.png", ImageRefresh.redirectTarget(from, "/d.png").toString());
        URL http = new URL("http://img.example.com/api/a.png");
        // A relative Location from an http page stays http, so it is refused too.
        assertNull(ImageRefresh.redirectTarget(http, "/d.png"));
    }

    @Test
    public void redirectStatuses() {
        for (int status : new int[] {301, 302, 303, 307, 308}) assertTrue(ImageRefresh.isRedirect(status));
        for (int status : new int[] {200, 204, 304, 400, 404, 500}) assertFalse(ImageRefresh.isRedirect(status));
    }

    @Test
    public void httpsCheck() throws Exception {
        assertTrue(ImageRefresh.isHttps(new URL("HTTPS://a.example/x")));
        assertFalse(ImageRefresh.isHttps(new URL("http://a.example/x")));
        assertFalse(ImageRefresh.isHttps(null));
    }
}
