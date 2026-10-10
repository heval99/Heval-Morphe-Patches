package app.template.patches.alarmclock.ads

import app.morphe.patcher.Fingerprint

// The shop data's entitlement check: d(ShopFeature) reads the owned flag of one feature from the
// shop list. Every ad gate (ad consent, the ad SDK start in ApplicationLazyInitializer and the
// activity ad checks) asks it for the AD_FREE feature. The class and method names are R8-renamed
// (l9.d in 26.06.0), so the fingerprint pins the name, the shape and the "feature" argument-check
// string, and skips the interface declaration (no body). The smoke test re-checks that the patched
// method is this one after any app update.
object ShopEntitlementFingerprint : Fingerprint(
    name = "d",
    returnType = "Z",
    parameters = listOf("Lcom/alarmclock/xtreme/shop/data/ShopFeature;"),
    strings = listOf("feature"),
    custom = { method, _ -> method.implementation != null },
)
