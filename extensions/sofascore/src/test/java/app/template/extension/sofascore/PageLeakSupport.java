package app.template.extension.sofascore;

import android.view.MenuItem;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;

/** Helpers for the page-state leak tests (plain JVM: no Activity or real MenuItem is created). */
final class PageLeakSupport {
    private PageLeakSupport() {
    }

    /**
     * A MenuItem stand-in that strongly reaches {@code page}, like a real item does through its
     * menu and the menu's Context. Only its identity is used, so every call answers null.
     */
    static MenuItem itemReaching(final Object page) {
        return (MenuItem) Proxy.newProxyInstance(MenuItem.class.getClassLoader(), new Class<?>[]{MenuItem.class},
            (proxy, method, args) -> {
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("toString".equals(method.getName())) return "item of " + page.getClass().getName();
                return null;
            });
    }

    /** Runs the collector until {@code ref} clears (bounded: about half a second at most). */
    static boolean collected(WeakReference<?> ref) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && ref.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        return ref.get() == null;
    }
}
