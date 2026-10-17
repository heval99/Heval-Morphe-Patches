package app.template.patches.scores365.misc.gms

import app.template.patches.scores365.misc.signature.spoofSignaturePatch
import app.template.patches.shared.Constants.COMPATIBILITY_365SCORES
import app.template.patches.shared.misc.gms.gmsCoreSupportPatch

// 365Scores' social login (SocialLoginActivity, tipster profile) uses play-services-auth
// GoogleSignIn, and match alerts arrive through FCM. Google Play services rejects the re-signed
// app for both, so they need MicroG RE. Off by default: everything else works without it, and
// the extension asks the user to install MicroG RE on every launch until it is installed. Pulls
// in "Spoof signature": FCM first needs a Firebase Installations ID, and 365Scores' API key
// rejects the re-signed certificate.
@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = "com.scores365",
    // SHA-1 of the Play Store signing certificate, read from the v2/v3 signature block of the
    // APKMirror 14.9.6 and 14.9.7 bundles (the merged APK's block no longer verifies, the certificate is intact).
    spoofedPackageSignature = "47f994bd2eac89f4aeea0aa1d95cb3ba3ec3f1a1",
    mainActivityOnCreateFingerprint = SplashOnCreateFingerprint,
    compatibility = COMPATIBILITY_365SCORES,
    default = false,
    dependencies = setOf(spoofSignaturePatch),
)
