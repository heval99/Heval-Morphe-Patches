package app.template.extension.hevalcontrols.ui;

import java.net.URL;

/**
 * The plain-Java rules of {@link OptionImageLoader}'s download, kept free of Android types so they
 * run in JVM unit tests ({@code ImageRefreshTest}): a refresh that fails in any way keeps the stale
 * cached image, and a redirect is followed only to an https URL, checked before the next request.
 */
final class ImageRefresh {
    /** Redirects followed per image (so at most this plus one requests). */
    static final int MAX_REDIRECTS = 3;

    interface Fetch<T> {
        /** The fresh result, or null when there is none. May throw. */
        T fetch() throws Exception;
    }

    /** The fresh result of {@code fetch}, else {@code stale} (when it gives null or throws anything). */
    static <T> T refreshOrStale(T stale, Fetch<T> fetch) {
        try {
            T fresh = fetch.fetch();
            return fresh != null ? fresh : stale;
        } catch (Throwable failure) {
            // Offline, a timeout, a bad image, out of memory: the cached copy is still usable.
            return stale;
        }
    }

    static boolean isHttps(URL url) {
        return url != null && "https".equalsIgnoreCase(url.getProtocol());
    }

    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** The https URL a redirect from {@code current} to {@code location} points at, or null. */
    static URL redirectTarget(URL current, String location) {
        if (location == null || location.isEmpty()) return null;
        try {
            URL next = new URL(current, location);
            return isHttps(next) ? next : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    private ImageRefresh() { }
}
