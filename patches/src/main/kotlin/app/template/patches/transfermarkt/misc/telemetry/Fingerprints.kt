package app.template.patches.transfermarkt.misc.telemetry

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string

// cordova-plugin-idfa's IdfaPlugin.getInfo(CallbackContext): reads the Google advertising ID and
// hands {aaid, trackingLimited} to the web app, which passes it to its ad and analytics tags.
// The plugin class and method names survive R8 but CallbackContext itself is renamed and the
// AdvertisingIdClient call moved into an R8 wrapper (4.4.0), so the parameter is matched by count
// only; the two JSON keys pin the method.
internal object IdfaGetInfoFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef ->
        classDef.type == "Lby/chemerisuk/cordova/advertising/IdfaPlugin;" &&
            method.name == "getInfo" && method.parameterTypes.size == 1
    },
    strings = listOf(
        string("aaid", StringComparisonType.CONTAINS),
        string("trackingLimited", StringComparisonType.CONTAINS),
    ),
)
