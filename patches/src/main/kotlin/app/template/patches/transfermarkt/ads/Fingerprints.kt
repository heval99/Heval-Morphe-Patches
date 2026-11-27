package app.template.patches.transfermarkt.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

// admob-plus-cordova-webview-ad's Plugin.initialize lambda (R8 merges it into a shared switch
// lambda since 4.4.0, so the method is also the body of other lambdas): on the UI thread it hands
// the app's WebView to the Google WebView API for Ads (MobileAds.registerWebView, inlined and its
// public name renamed since 4.4.0), reloads the page and logs "Integrated the WebView API for Ads
// in <url>". The WebView.reload call and the log prefix pin it.
internal object WebViewAdIntegrationFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "Landroid/webkit/WebView;", name = "reload"),
        string("Integrated the WebView API for Ads in ", StringComparisonType.STARTS_WITH),
    ),
)
