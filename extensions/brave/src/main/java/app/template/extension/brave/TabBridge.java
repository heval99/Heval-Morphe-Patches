package app.template.extension.brave;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection bridge to Brave's tab (measured on 1.100.58): the JavaScript runs through
 * {@code Tab.loadUrl(new LoadUrlParams(0, "javascript:..."))}, which executes it in the page's main
 * world. The Tab, its URL and the load method are found by kept names and shapes, never by R8 names.
 * Must be called on the main thread.
 */
final class TabBridge {
    private static final String TAB_INTERFACE = "org.chromium.chrome.browser.tab.Tab";
    private static final String LOAD_URL_PARAMS = "org.chromium.content_public.browser.LoadUrlParams";
    private static final int MAX_URL_CHARS = 1_500_000;

    private static final Map<Class<?>, Method> LOAD_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> GET_URL = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<Method>> GURL_GETTERS = new ConcurrentHashMap<>();

    private TabBridge() { }

    /** Runs the statements in the tab's page. Returns false (never throws) when that was not possible. */
    static boolean run(Object tab, String javascript) {
        if (tab == null || javascript == null) return false;
        try {
            String url = javascriptUrl(javascript);
            if (url.length() > MAX_URL_CHARS) return false;
            Method load = loadMethod(tab.getClass());
            if (load == null) return false;
            Object params = newParams(load.getParameterTypes()[0], url);
            if (params == null) return false;
            load.invoke(tab, params);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The tab's current http(s) URL, or null. */
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
                } catch (Throwable ignored) {
                    // try the next getter
                }
            }
            return pickUrl(values);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method loadMethod(Class<?> tabClass) {
        Method cached = LOAD_METHODS.get(tabClass);
        if (cached != null) return cached;
        Method chosen = null;
        for (Class<?> i : allInterfaces(tabClass)) {
            if (!TAB_INTERFACE.equals(i.getName())) continue;
            for (Method m : i.getDeclaredMethods()) {
                if (takesOnlyParams(m)) {
                    chosen = m;
                    break;
                }
            }
        }
        if (chosen == null) {
            for (Class<?> k = tabClass; k != null && k != Object.class && chosen == null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) && takesOnlyParams(m)) {
                        chosen = m;
                        break;
                    }
                }
            }
        }
        if (chosen != null) {
            chosen.setAccessible(true);
            LOAD_METHODS.put(tabClass, chosen);
        }
        return chosen;
    }

    private static boolean takesOnlyParams(Method m) {
        Class<?>[] p = m.getParameterTypes();
        return p.length == 1 && LOAD_URL_PARAMS.equals(p[0].getName());
    }

    private static Object newParams(Class<?> paramsClass, String url) throws Exception {
        Object[][] tries = {
            { new Class<?>[] { int.class, String.class }, new Object[] { 0, url } },
            { new Class<?>[] { String.class }, new Object[] { url } },
            { new Class<?>[] { String.class, int.class }, new Object[] { url, 0 } },
        };
        for (Object[] attempt : tries) {
            try {
                Constructor<?> c = paramsClass.getDeclaredConstructor((Class<?>[]) attempt[0]);
                c.setAccessible(true);
                return c.newInstance((Object[]) attempt[1]);
            } catch (NoSuchMethodException ignored) {
                // try the next constructor shape
            }
        }
        return null;
    }

    private static Method findNoArg(Class<?> type, String name) {
        try {
            Method m = type.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            // search the hierarchy
        }
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == 0 && !Modifier.isStatic(m.getModifiers())) {
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
                } catch (Throwable ignored) {
                    // skip it
                }
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

    // ---- pure helpers (JVM tested) ----

    /**
     * The GURL spec among its getter values: the LONGEST value starting with http:// or https:// and
     * without a NUL, which must contain every other such value (the other getters return parts of it).
     * An inconsistent set gives null.
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

    /** {@code javascript:} URL that runs the statements and evaluates to undefined (the page stays). */
    static String javascriptUrl(String body) {
        return "javascript:" + percentEncode("void(function(){" + body + "\n}())");
    }

    /** Same output as android.net.Uri.encode: letters, digits and _-!.~'()* stay, the rest is %XX of UTF-8. */
    static String percentEncode(String value) {
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
