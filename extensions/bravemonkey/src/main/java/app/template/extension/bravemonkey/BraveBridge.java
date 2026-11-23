/*
 * Brave Userscripts: reflection bridge to Brave's Tab. Replaces upstream ChromeBridge from
 * Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later). SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.template.extension.bravemonkey;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-reflection bridge to Brave's {@code TabImpl}, measured on Brave 1.100.58 (emulator spike):
 * <ul>
 *   <li>{@link #run}: the renamed {@code Tab.loadUrl(LoadUrlParams)} with
 *       {@code new LoadUrlParams(0, "javascript:" + encoded)}; the page runs it in its MAIN world.</li>
 *   <li>{@link #urlOf}: {@code Tab.getUrl()} returns a GURL whose getters are obfuscated; the spec is
 *       the LONGEST http(s) string among its no-arg String getters without a NUL (other getters
 *       return the fragment, query, path or host, all contained in the spec); every other kept
 *       candidate must be contained in it or the URL is rejected.</li>
 *   <li>{@link #isIncognito}: kept {@code isIncognito()}; a reflection failure counts as incognito.</li>
 * </ul>
 * Call {@link #run}, {@link #urlOf} and {@link #isIncognito} on the main thread. The pure helpers
 * ({@link #pickUrl}, {@link #percentEncode}, {@link #wrap}, {@link #javascriptUrl}) use no android.*
 * class and are unit tested on the JVM; keep android statics out of this class.
 */
final class BraveBridge {
    static final String TAB_INTERFACE = "org.chromium.chrome.browser.tab.Tab";
    static final String LOAD_URL_PARAMS = "org.chromium.content_public.browser.LoadUrlParams";
    /** Encoded javascript: URLs longer than this are skipped (logged), not loaded. */
    static final int MAX_URL_CHARS = 1_500_000;
    /** PageTransition.LINK; the value the spike used. */
    private static final int TRANSITION = 0;

    private static final Map<Class<?>, Method> LOAD_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> GET_URL = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<Method>> GURL_GETTERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> INCOGNITO = new ConcurrentHashMap<>();

    private BraveBridge() { }

    // ---------------------------------------------------------------- reflection (main thread)

    /** Loads {@code javascript} (statements) into the tab's page. Never throws. */
    static boolean run(Object tab, String javascript) {
        if (tab == null || javascript == null) return false;
        try {
            String url = javascriptUrl(javascript);
            if (url.length() > MAX_URL_CHARS) {
                Logger.w("payload skipped: encoded javascript: URL is " + url.length()
                        + " chars (limit " + MAX_URL_CHARS + ")");
                return false;
            }
            Method load = loadMethod(tab.getClass());
            if (load == null) {
                Logger.w("no Tab method taking LoadUrlParams on " + tab.getClass().getName());
                return false;
            }
            Object params = newParams(load.getParameterTypes()[0], url);
            if (params == null) {
                Logger.w("no usable LoadUrlParams constructor");
                return false;
            }
            load.invoke(tab, params);
            return true;
        } catch (Throwable error) {
            Logger.e("run failed", error);
            return false;
        }
    }

    /** The tab's current http(s) URL, or null. Never throws. */
    static String urlOf(Object tab) {
        if (tab == null) return null;
        try {
            Method getUrl = GET_URL.get(tab.getClass());
            if (getUrl == null) {
                getUrl = findNoArg(tab.getClass(), "getUrl");
                if (getUrl == null) return null;
                GET_URL.put(tab.getClass(), getUrl);
            }
            Object gurl = getUrl.invoke(tab);
            if (gurl == null) return null;
            if (gurl instanceof CharSequence) return pickUrl(Arrays.asList(gurl.toString()));
            List<Method> getters = GURL_GETTERS.get(gurl.getClass());
            if (getters == null) {
                getters = stringGetters(gurl.getClass());
                GURL_GETTERS.put(gurl.getClass(), getters);
            }
            List<String> values = new ArrayList<>(getters.size());
            for (Method m : getters) {
                try {
                    Object value = m.invoke(gurl);
                    if (value instanceof String) values.add((String) value);
                } catch (Throwable ignored) { }
            }
            return pickUrl(values);
        } catch (Throwable error) {
            Logger.e("urlOf failed", error);
            return null;
        }
    }

    /** {@code Tab.isIncognito()}; any reflection failure is reported as incognito (do not inject). */
    static boolean isIncognito(Object tab) {
        if (tab == null) return true;
        try {
            Method m = INCOGNITO.get(tab.getClass());
            if (m == null) {
                m = findNoArg(tab.getClass(), "isIncognito");
                if (m == null) return true;
                INCOGNITO.put(tab.getClass(), m);
            }
            Object value = m.invoke(tab);
            return !(value instanceof Boolean) || (Boolean) value;
        } catch (Throwable error) {
            Logger.e("isIncognito failed", error);
            return true;
        }
    }

    private static Method loadMethod(Class<?> tabClass) {
        Method cached = LOAD_METHODS.get(tabClass);
        if (cached != null) return cached;

        // 1. Prefer the method declared on the Tab interface.
        Map<String, Method> onTab = new LinkedHashMap<>();
        Set<Class<?>> interfaces = allInterfaces(tabClass);
        for (Class<?> i : interfaces) {
            if (!TAB_INTERFACE.equals(i.getName())) continue;
            for (Method m : i.getDeclaredMethods()) {
                if (takesOnlyParams(m)) onTab.put(signature(m), m);
            }
        }
        Method chosen = pickOne(onTab, "Tab interface");
        if (chosen == null) {
            // 2. Fall back to the class hierarchy and every interface.
            Map<String, Method> all = new LinkedHashMap<>();
            for (Class<?> k = tabClass; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) && takesOnlyParams(m)) {
                        String key = signature(m);
                        if (!all.containsKey(key)) all.put(key, m);
                    }
                }
            }
            for (Class<?> i : interfaces) {
                for (Method m : i.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) && takesOnlyParams(m)) {
                        String key = signature(m);
                        if (!all.containsKey(key)) all.put(key, m);
                    }
                }
            }
            chosen = pickOne(all, "class hierarchy");
        }
        if (chosen != null) {
            chosen.setAccessible(true);
            LOAD_METHODS.put(tabClass, chosen);
            Logger.i("load method " + chosen.getDeclaringClass().getName() + "." + chosen.getName());
        }
        return chosen;
    }

    private static Method pickOne(Map<String, Method> candidates, String where) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() > 1) {
            Logger.w(candidates.size() + " LoadUrlParams methods on the " + where + ": "
                    + candidates.keySet() + "; using the first");
        }
        return candidates.values().iterator().next();
    }

    private static boolean takesOnlyParams(Method m) {
        Class<?>[] p = m.getParameterTypes();
        return p.length == 1 && LOAD_URL_PARAMS.equals(p[0].getName());
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.toString(m.getParameterTypes());
    }

    private static Object newParams(Class<?> paramsClass, String url) throws Exception {
        Object[][] tries = {
            { new Class<?>[] { int.class, String.class }, new Object[] { TRANSITION, url } },
            { new Class<?>[] { String.class }, new Object[] { url } },
            { new Class<?>[] { String.class, int.class }, new Object[] { url, TRANSITION } },
        };
        for (Object[] attempt : tries) {
            try {
                Constructor<?> c = paramsClass.getDeclaredConstructor((Class<?>[]) attempt[0]);
                c.setAccessible(true);
                return c.newInstance((Object[]) attempt[1]);
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static Method findNoArg(Class<?> type, String name) {
        try {
            Method m = type.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) { }
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == 0
                        && !Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static List<Method> stringGetters(Class<?> type) {
        List<Method> out = new ArrayList<>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0 || m.getReturnType() != String.class
                        || Modifier.isStatic(m.getModifiers())) continue;
                try {
                    m.setAccessible(true);
                    out.add(m);
                } catch (Throwable ignored) { }
            }
        }
        return out;
    }

    private static Set<Class<?>> allInterfaces(Class<?> c) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (; c != null; c = c.getSuperclass()) collect(c, out);
        return out;
    }

    private static void collect(Class<?> c, Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) {
            if (out.add(i)) collect(i, out);
        }
    }

    // ---------------------------------------------------------------- pure helpers (JVM tested)

    /**
     * The GURL spec among its getter values. Getters also return the fragment, query, path and host
     * (a page https://evil.example/#https://bank.example/ has a getter returning
     * "https://bank.example/"), so the spec is the LONGEST value that starts with "http://" or
     * "https://" and has no NUL (the NUL-joined serialisation is excluded by that rule). The full
     * spec contains every other component, so the choice must contain every other kept candidate;
     * otherwise the set is inconsistent and the result is null (fail closed).
     */
    static String pickUrl(Iterable<String> candidates) {
        if (candidates == null) return null;
        List<String> kept = new ArrayList<>();
        String best = null;
        for (String s : candidates) {
            if (s == null || s.indexOf('\u0000') >= 0) continue;
            if (!(s.startsWith("http://") || s.startsWith("https://"))) continue;
            kept.add(s);
            if (best == null || s.length() > best.length()) best = s;
        }
        if (best == null) return null;
        for (String s : kept) {
            if (!best.contains(s)) return null;
        }
        return best;
    }

    /** Wraps statements so the javascript: URL evaluates to undefined (the page is not replaced). */
    static String wrap(String body) {
        return "void(function(){" + body + "\n}())";
    }

    static String javascriptUrl(String body) {
        return "javascript:" + percentEncode(wrap(body));
    }

    /**
     * Same output as {@code android.net.Uri.encode(s)}: letters, digits and {@code _-!.~'()*} stay,
     * everything else becomes upper-case %XX of its UTF-8 bytes (a surrogate pair is one code point).
     */
    static String percentEncode(String value) {
        if (value == null) return null;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + bytes.length / 2);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '_' || b == '-' || b == '!' || b == '.' || b == '~'
                    || b == '\'' || b == '(' || b == ')' || b == '*') {
                out.append((char) b);
            } else {
                out.append('%').append(hex[b >> 4]).append(hex[b & 0xF]);
            }
        }
        return out.toString();
    }
}
