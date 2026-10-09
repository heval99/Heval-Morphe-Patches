package app.template.patches.iptvremote.misc.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string

// IPTV runs its own ad mediation layer: an abstract provider (R8-renamed, `a4` in 9.1.25)
// with two concrete subclasses. The ad-supported one (`b4`) rotates Yandex Mobile Ads,
// Wortise and a third network across banner/interstitial/instream placements; the other
// (`i5`) is the app's built-in no-ads provider (empty placement list, no-op interstitial,
// null instream page). Its `f(Context)J` reads the instream preload lead time from remote
// config under this key; IptvFreeApplication.onCreate also holds the string but returns
// void, so the return type and parameter pin the provider.
object AdProviderInstreamLeadFingerprint : Fingerprint(
    returnType = "J",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("instream_preload_lead_sec"),
)

// The ad consent gate (`e4` in 9.1.25) defers every ad action (SDK consent setup, the idle
// placement preloader, Yandex/Wortise interstitial loads, instream preload) until UMP/Yandex
// consent is resolved. Its "consent resolved" method replays the queued actions and logs
// failures under this tag; the patch then neuters the sibling enqueue-or-run method.
object AdConsentGateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string("AdConsentGate"),
        string("Deferred consent action", StringComparisonType.STARTS_WITH),
    ),
)
