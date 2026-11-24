package app.template.patches.sofascore.misc.gms

import app.template.patches.shared.Constants.COMPATIBILITY_SOFASCORE
import app.template.patches.shared.misc.gms.gmsCoreSupportPatch
import app.template.patches.sofascore.misc.signature.spoofSignaturePatch

// Sofascore's match alerts arrive through FCM (FCMListenerService) and Google login goes through
// androidx.credentials' Play services provider. Google Play services rejects the re-signed app
// for both, so they need MicroG RE (issues #24, #25). Off by default: everything else works
// without it, and the extension asks the user to install MicroG RE on every launch until it is
// installed. Pulls in "Spoof signature", because Firebase checks the app's certificate too.
@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = "com.sofascore.results",
    // SHA-1 of the Play Store signing certificate, read from the v2/v3 signature block of the
    // APKMirror 26.09.28 bundle's base APK (the merged APK's block no longer verifies).
    spoofedPackageSignature = "e235776bc0ad3837b01359177c438658b1424d23",
    mainActivityOnCreateFingerprint = StartActivityOnCreateFingerprint,
    compatibility = COMPATIBILITY_SOFASCORE,
    default = false,
    // A clone has another package name, which breaks Firebase push (Spoof signature covers only the
    // original package), so the Package name option is not offered (2026-11-24).
    allowClone = false,
    description = "Only for phones without Google Play services; notifications stop unless MicroG RE is installed.",
    dependencies = setOf(spoofSignaturePatch),
)
