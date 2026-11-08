package app.template.extension.webviewads;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * Blocks ad and tracking hosts inside a WebView. The news apps render the article page itself, so
 * the ads are the website's own requests (Google Ad Manager, AdOcean/Gemius, the usual RTB
 * networks) rather than SDK calls; intercepting them before they leave the WebView removes the
 * slots without touching the page content.
 */
public final class AdBlock {

    private AdBlock() {
    }

    // Host suffixes. A request is blocked when its host equals an entry or ends with ".<entry>".
    // The three c.<publisher>.dk hosts are the JPPol Snowplow collectors.
    private static final String[] HOSTS = {
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "smartadserver.com",
        "casalemedia.com",
        "33across.com",
        "sharethrough.com",
        "teads.tv",
        "yieldmo.com",
        "bidswitch.net",
        "triplelift.com",
        "indexexchange.com",
        "sovrn.com",
        "gumgum.com",
        "spotxchange.com",
        "innovid.com",
        "serving-sys.com",
        "flashtalking.com",
        "adform.net",
        "1rx.io",
        "agkn.com",
        "mathtag.com",
        "demdex.net",
        "omtrdc.net",
        "gemius.pl",
        "gemius.com",
        "adocean.pl",
        "c.jyllands-posten.dk",
        "c.ekstrabladet.dk",
        "c.politiken.dk",
    };

    /** Installs a blocking client on [webView], replacing any stock/default client. */
    public static void install(WebView webView) {
        if (webView == null) {
            return;
        }
        webView.setWebViewClient(new BlockingClient());
    }

    /** True when [url] targets an ad or tracking host. Never throws. */
    public static boolean isAdUrl(String url) {
        if (url == null) {
            return false;
        }
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("adservice.google.")) {
            return true;
        }
        for (String blocked : HOSTS) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }

    /** The empty response returned for a blocked request. */
    public static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    private static final class BlockingClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request != null && isAdUrl(request.getUrl() == null ? null : request.getUrl().toString())) {
                return emptyResponse();
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (isAdUrl(url)) {
                return emptyResponse();
            }
            return super.shouldInterceptRequest(view, url);
        }
    }
}
