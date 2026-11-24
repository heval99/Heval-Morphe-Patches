/*
 * Copyright (C) 2026 Alastor Kaneki
 * Ported from Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later); parts adapted from Violentmonkey (MIT).
 * Modified for Brave Android.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * URL matcher compatible with common userscript @match/@include/@exclude/@exclude-match rules.
 * Only http(s) pages are injectable. Pure Java (no android.*) so it is unit tested on the JVM.
 */
final class UrlPatternMatcher {
    private UrlPatternMatcher() { }

    static boolean matches(Userscript script, String url) {
        if (script == null || !script.enabled || url == null || url.trim().isEmpty()) return false;
        String normalized = url.trim();
        if (!isInjectableScheme(normalized)) return false;

        boolean positive = false;
        for (String pattern : script.matches) {
            if (matchesChromePattern(pattern, normalized)) {
                positive = true;
                break;
            }
        }
        if (!positive) {
            for (String pattern : script.includes) {
                if (matchesInclude(pattern, normalized)) {
                    positive = true;
                    break;
                }
            }
        }
        if (!positive) return false;

        for (String pattern : script.excludeMatches) {
            if (matchesChromePattern(pattern, normalized)) return false;
        }
        for (String pattern : script.excludes) {
            if (matchesInclude(pattern, normalized)) return false;
        }
        return true;
    }

    /** Chrome match pattern ({@code scheme://host/path}); without "://" it falls back to a glob. */
    static boolean matchesChromePattern(String pattern, String url) {
        if (pattern == null || url == null) return false;
        String value = pattern.trim();
        if (value.isEmpty()) return false;
        if ("<all_urls>".equalsIgnoreCase(value)) return isInjectableScheme(url);

        int schemeSeparator = value.indexOf("://");
        if (schemeSeparator <= 0) return matchesInclude(value, url);

        String patternScheme = value.substring(0, schemeSeparator).toLowerCase(Locale.US);
        String remainder = value.substring(schemeSeparator + 3);
        int slash = remainder.indexOf('/');
        String patternAuthority = slash >= 0 ? remainder.substring(0, slash) : remainder;
        String patternPath = slash >= 0 ? remainder.substring(slash) : "/*";
        String patternPort = null;
        String patternHost = patternAuthority;
        int colon = patternAuthority.lastIndexOf(':');
        if (colon > 0 && patternAuthority.indexOf(']') < colon) {
            patternHost = patternAuthority.substring(0, colon);
            patternPort = patternAuthority.substring(colon + 1);
        }

        Parts parts = Parts.of(url);
        if (parts == null) return false;

        boolean schemeMatches;
        if ("*".equals(patternScheme)) {
            schemeMatches = "http".equals(parts.scheme) || "https".equals(parts.scheme);
        } else {
            schemeMatches = patternScheme.equals(parts.scheme);
        }
        if (!schemeMatches) return false;

        boolean hostMatches;
        String host = parts.host;
        if ("*".equals(patternHost)) {
            hostMatches = !host.isEmpty();
        } else if (patternHost.startsWith("*.")) {
            String base = patternHost.substring(2).toLowerCase(Locale.US);
            hostMatches = host.equals(base) || host.endsWith("." + base);
        } else {
            hostMatches = patternHost.toLowerCase(Locale.US).equals(host);
        }
        if (!hostMatches) return false;
        if (patternPort != null && !"*".equals(patternPort) && !patternPort.equals(parts.port)) {
            return false;
        }
        return globMatches(patternPath, parts.pathAndQuery);
    }

    /** @include/@exclude rule: a /regex/ or a glob over the whole URL. */
    static boolean matchesInclude(String pattern, String url) {
        if (pattern == null || url == null) return false;
        String value = pattern.trim();
        if (value.isEmpty()) return false;

        if (value.length() > 2 && value.startsWith("/") && value.endsWith("/")) {
            try {
                return Pattern.compile(value.substring(1, value.length() - 1)).matcher(url).find();
            } catch (PatternSyntaxException ignored) {
                return false;
            }
        }
        return globMatches(value, url);
    }

    static boolean isInjectableScheme(String url) {
        if (url == null) return false;
        String normalized = url.toLowerCase(Locale.US);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    static int countMatches(List<Userscript> scripts, String url) {
        if (scripts == null) return 0;
        int count = 0;
        for (Userscript script : scripts) {
            if (matches(script, url)) count++;
        }
        return count;
    }

    private static boolean globMatches(String glob, String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char character = glob.charAt(index);
            switch (character) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append('.');
                    break;
                case '\\':
                    regex.append("\\\\");
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '$':
                case '^':
                case '{':
                case '}':
                case '+':
                case '|':
                    regex.append('\\').append(character);
                    break;
                default:
                    regex.append(character);
                    break;
            }
        }
        regex.append('$');
        try {
            return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value).matches();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    /**
     * Lenient URL split (scheme, host, port, path+query; the fragment is dropped). Chromium hands
     * us canonical URLs, but java.net.URI rejects some characters it allows, so no URI here.
     */
    static final class Parts {
        final String scheme;
        final String host;
        final String port;
        final String pathAndQuery;

        private Parts(String scheme, String host, String port, String pathAndQuery) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.pathAndQuery = pathAndQuery;
        }

        static Parts of(String url) {
            if (url == null) return null;
            int sep = url.indexOf("://");
            if (sep <= 0) return null;
            String scheme = url.substring(0, sep).toLowerCase(Locale.US);
            String rest = url.substring(sep + 3);
            int hash = rest.indexOf('#');
            if (hash >= 0) rest = rest.substring(0, hash);
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?') { end = i; break; }
            }
            String authority = rest.substring(0, end);
            String pathAndQuery = rest.substring(end);
            if (pathAndQuery.isEmpty() || pathAndQuery.charAt(0) == '?') {
                pathAndQuery = "/" + pathAndQuery;
            }
            int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1);
            String host = authority;
            String port = "";
            int colon = authority.lastIndexOf(':');
            if (colon >= 0 && authority.indexOf(']') < colon) {
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            }
            return new Parts(scheme, host.toLowerCase(Locale.US), port, pathAndQuery);
        }
    }
}
