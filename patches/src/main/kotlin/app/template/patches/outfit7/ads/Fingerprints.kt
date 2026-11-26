package app.template.patches.outfit7.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

// Outfit7's Felis layer. Every game ad request from the Unity side goes through the interface
// InventoryBinding (a kept name); its single concrete implementer is R8-renamed (Af.n in My
// Talking Tom 26.5.4, Jf.m in My Talking Tom 2 26.5.2), so it is found by the interface.
internal const val INVENTORY_BINDING = "Lcom/outfit7/engine/inventory/InventoryBinding;"

// The splash-ad loader coroutine (obfuscated class, kept log literal). It returns without
// loading when the "ads removed" billing check answers true; that check is the only
// no-argument boolean invoke-interface in the method, which the patch rewrites to true.
internal object SplashAdLoadFingerprint : Fingerprint(
    filters = listOf(string("Load splash ad")),
)
