// Based on bearinmindcat/morphe-patches (GPL-3.0) @ 5f79152, extensions/samsungkeyboard/src/main/java/org/ungoogled/samsungkeyboard/Gifs.java.
// Modified for Heval's Morphe Patches on 2026-11-13: package renamed to app.template.extension.samsungkeyboard.

package app.template.extension.samsungkeyboard;

/**
 * Always use GIPHY. The keyboard picks its GIF providers from the phone's language: 2 = Tenor only (German, Chinese,
 * Arabic, Turkish), 4 = Tenor, then GIPHY (French, Korean, Spanish, Russian, Italian, Japanese, Portuguese),
 * 3 = GIPHY, then Tenor (English), 0 = GIPHY, then Tenor with English searches (every other language). Tenor's API
 * is closed to apps outside Google since 2026-06-30, so a Tenor-only board stays empty.
 */
public final class Gifs {
    private Gifs() {
    }

    /** The keyboard's answer for a language, with GIPHY first wherever Tenor came first or alone. */
    public static int giphyFirst(int providers) {
        return providers == 2 || providers == 4 ? 3 : providers;
    }
}
