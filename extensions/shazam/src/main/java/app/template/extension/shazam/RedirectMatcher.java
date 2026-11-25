package app.template.extension.shazam;

import java.net.URI;
import java.util.Locale;

/**
 * Decides whether a redirect that reached the exported receiver is the expected Spotify callback.
 * Plain Java (no Android classes) so it is unit tested on the JVM.
 *
 * <p>Scheme and host compare case-insensitively; port and path must match exactly, the redirect
 * may carry no userinfo, and when the login request sent a {@code state} the redirect must echo
 * it. The SDK reads the response from the query or, for a token response, the fragment, so the
 * state is accepted from either, but never from two places that disagree.
 */
final class RedirectMatcher {
    private RedirectMatcher() {
    }

    /**
     * @param callback the redirect_uri of the login request
     * @param incoming the URI the receiver was started with
     * @param state    the request's state, or null when none was sent
     */
    static boolean matches(String callback, String incoming, String state) {
        try {
            if (callback == null || incoming == null) return false;
            URI expected = URI.create(callback);
            URI actual = URI.create(incoming);
            if (expected.isOpaque() || actual.isOpaque()) return false;
            if (actual.getRawUserInfo() != null) return false;
            if (!equalsIgnoreCase(expected.getScheme(), actual.getScheme())) return false;
            if (!equalsIgnoreCase(expected.getHost(), actual.getHost())) return false;
            if (expected.getPort() != actual.getPort()) return false;
            if (!expected.getRawPath().equals(actual.getRawPath())) return false;
            if (state == null) return true;

            String fromQuery = param(actual.getRawQuery(), "state");
            String fromFragment = param(actual.getRawFragment(), "state");
            if (fromQuery != null && fromFragment != null && !fromQuery.equals(fromFragment)) return false;
            String received = fromQuery != null ? fromQuery : fromFragment;
            return received != null && state.equals(received);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) return false;
        return a.toLowerCase(Locale.ENGLISH).equals(b.toLowerCase(Locale.ENGLISH));
    }

    /** The decoded first value of {@code name} in a raw query-style string, or null. */
    private static String param(String raw, String name) {
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (decode(key).equals(name)) return eq < 0 ? "" : decode(pair.substring(eq + 1));
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
