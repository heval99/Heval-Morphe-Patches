package app.template.extension.brave;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;

/** Android-facing hooks of "Strip tracking parameters". Each one fails open: on any error the original is used. */
public final class ShareHooks {
    private ShareHooks() { }

    /**
     * Called with the clip Brave is about to put on the clipboard; returns the clip to put there. The clip
     * is only rebuilt when a text or an HTML link changed (same description, plain text and HTML kept).
     */
    public static ClipData sanitizeClip(ClipData clip) {
        try {
            if (clip == null || clip.getItemCount() != 1) return clip;
            ClipData.Item item = clip.getItemAt(0);
            if (item.getUri() != null || item.getIntent() != null) return clip;
            CharSequence text = item.getText();
            if (text == null) return clip;
            String html = item.getHtmlText();
            String original = text.toString();
            String clean = UrlStripper.sanitize(original);
            String cleanHtml = UrlStripper.sanitizeHtml(html);
            if (clean == original && cleanHtml == html) return clip;
            ClipDescription description = clip.getDescription();
            return new ClipData(description, new ClipData.Item(clean, cleanHtml));
        } catch (Throwable ignored) {
            return clip;
        }
    }

    /** Called with the String Brave is about to send as EXTRA_TEXT; returns the text to send. */
    public static String sanitizeText(String text) {
        try {
            return UrlStripper.sanitize(text);
        } catch (Throwable ignored) {
            return text;
        }
    }

    /** Called with the finished share Intent before it is returned; cleans EXTRA_TEXT in place. */
    public static void sanitizeIntent(Intent intent) {
        try {
            if (intent == null) return;
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text == null) return;
            String original = text.toString();
            String clean = UrlStripper.sanitize(original);
            if (clean != original) intent.putExtra(Intent.EXTRA_TEXT, clean);
        } catch (Throwable ignored) {
            // leave the intent as it is
        }
    }
}
