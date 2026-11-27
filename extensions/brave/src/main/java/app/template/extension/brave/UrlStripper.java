package app.template.extension.brave;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes marketing and click-id query parameters from URLs (pure Java, unit tested on the JVM).
 * Only the parameters named here are removed; every other parameter, the path and the fragment stay.
 */
public final class UrlStripper {
    private static final Set<String> TRACKING_EXACT = new HashSet<>(Arrays.asList(
            "gclid", "gbraid", "wbraid", "fbclid", "msclkid", "twclid", "dclid",
            "igshid", "mc_eid", "vero_id", "_openstat"
    ));

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s<>\"]+");
    private static final Pattern ATTRIBUTE_URL = Pattern.compile("(?i)\\b(href|src)(\\s*=\\s*)([\"'])(.*?)\\3");

    private UrlStripper() { }

    /**
     * Strips tracking parameters from a URL, or from every URL inside a text. Returns the SAME string
     * instance when nothing was removed (no trimming, no rewriting), so callers can compare by identity.
     */
    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) return input;
        Matcher matcher = URL_PATTERN.matcher(input);
        StringBuilder out = null;
        int copied = 0;
        while (matcher.find()) {
            int end = trimTrailingPunctuation(input, matcher.start(), matcher.end());
            String url = input.substring(matcher.start(), end);
            String clean = sanitizeSingleUrl(url);
            if (clean.equals(url)) continue;
            if (out == null) out = new StringBuilder(input.length());
            out.append(input, copied, matcher.start()).append(clean);
            copied = end;
        }
        if (out == null) return input;
        return out.append(input, copied, input.length()).toString();
    }

    /**
     * Cleans the links of an HTML clip: only href and src attribute values are touched (decoded from
     * &amp; before the parameters are checked and encoded again after). Same-instance rule as {@link #sanitize}.
     */
    public static String sanitizeHtml(String html) {
        if (html == null || html.isEmpty()) return html;
        Matcher matcher = ATTRIBUTE_URL.matcher(html);
        StringBuilder out = null;
        int copied = 0;
        while (matcher.find()) {
            String value = matcher.group(4);
            String plain = value.replace("&amp;", "&");
            String clean = sanitize(plain);
            if (clean == plain || clean.equals(plain)) continue;
            if (out == null) out = new StringBuilder(html.length());
            out.append(html, copied, matcher.start(4)).append(clean.replace("&", "&amp;"));
            copied = matcher.end(4);
        }
        if (out == null) return html;
        return out.append(html, copied, html.length()).toString();
    }

    /** End of the URL that starts at [start]: sentence punctuation and an unbalanced ")" are not part of it. */
    private static int trimTrailingPunctuation(String s, int start, int end) {
        while (end > start) {
            char c = s.charAt(end - 1);
            if (".,;:!?'\"]}>".indexOf(c) >= 0) {
                end--;
            } else if (c == ')' && count(s, start, end, ')') > count(s, start, end, '(')) {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    private static int count(String s, int start, int end, char c) {
        int n = 0;
        for (int i = start; i < end; i++) if (s.charAt(i) == c) n++;
        return n;
    }
    private static String sanitizeSingleUrl(String url) {
        try {
            int hashIdx = url.indexOf('#');
            String fragment = "";
            String beforeHash = url;
            if (hashIdx >= 0) {
                fragment = url.substring(hashIdx);
                beforeHash = url.substring(0, hashIdx);
            }
            int queryIdx = beforeHash.indexOf('?');
            if (queryIdx < 0) return url;

            String base = beforeHash.substring(0, queryIdx);
            String query = beforeHash.substring(queryIdx + 1);
            if (query.isEmpty()) return url;

            boolean mediaHost = isSiStrippableMediaHost(base);
            StringBuilder kept = new StringBuilder();
            boolean removed = false;
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String key = decodePercentOnce(eq >= 0 ? pair.substring(0, eq) : pair);
                if (isTrackingParam(key, mediaHost)) {
                    removed = true;
                    continue;
                }
                if (kept.length() > 0) kept.append('&');
                kept.append(pair);
            }
            if (!removed) return url;
            return kept.length() > 0 ? base + "?" + kept + fragment : base + fragment;
        } catch (Throwable t) {
            return url;
        }
    }

    private static boolean isSiStrippableMediaHost(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            if (host == null || host.isEmpty()) return false;
            String h = host.toLowerCase(Locale.ROOT);
            while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
            return "youtube.com".equals(h) || h.endsWith(".youtube.com")
                    || "youtu.be".equals(h) || h.endsWith(".youtu.be")
                    || "spotify.com".equals(h) || h.endsWith(".spotify.com");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Decodes %XX and '+' once without failing on malformed escapes. */
    private static String decodePercentOnce(String raw) {
        if (raw == null || raw.isEmpty() || (raw.indexOf('%') < 0 && raw.indexOf('+') < 0)) return raw;
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
        int len = raw.length();
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c == '+') {
                out.write(' ');
            } else if (c == '%' && i + 2 < len) {
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                } else {
                    out.write('%');
                }
            } else if (c < 0x80) {
                out.write(c);
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static boolean isTrackingParam(String key, boolean mediaHost) {
        if (key == null) return false;
        String lower = key.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("utm_")) return true;
        if (TRACKING_EXACT.contains(lower)) return true;
        // "si" is only a tracker on YouTube and Spotify links
        return "si".equals(lower) && mediaHost;
    }
}
