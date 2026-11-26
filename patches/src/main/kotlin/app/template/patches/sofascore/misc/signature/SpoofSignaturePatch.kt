package app.template.patches.sofascore.misc.signature

import app.template.patches.shared.Constants.COMPATIBILITY_SOFASCORE
import app.template.patches.shared.misc.signature.spoofSignaturePatch

// The Play Store signing certificate (CN=Sofa Score, OU=Mobile, O=SofaScore, SHA-1
// e235776bc0ad3837b01359177c438658b1424d23), DER as base64, read from the v2/v3 signing block of
// the APKMirror 26.09.28 bundle's base APK. Firebase (Installations, so FCM, plus Auth and Remote
// Config) sends this certificate's SHA-1 as X-Android-Cert; re-signed, push and login fail
// (issues #24, #25).
internal const val SOFASCORE_CERTIFICATE =
    "MIICOzCCAaSgAwIBAgIET9ILQTANBgkqhkiG9w0BAQUFADBhMQswCQYDVQQGEwJVSzELMAkGA1UECBMCVUsxCzAJBgNVBAcTAlVLMRIwEAYDVQQKEwlTb2ZhU2NvcmUxDzANBgNVBAsTBk1vYmlsZTETMBEGA1UEAxMKU29mYSBTY29yZTAgFw0xMjA2MDgxNDI1MDVaGA8yMDUyMDUyOTE0MjUwNVowYTELMAkGA1UEBhMCVUsxCzAJBgNVBAgTAlVLMQswCQYDVQQHEwJVSzESMBAGA1UEChMJU29mYVNjb3JlMQ8wDQYDVQQLEwZNb2JpbGUxEzARBgNVBAMTClNvZmEgU2NvcmUwgZ8wDQYJKoZIhvcNAQEBBQADgY0AMIGJAoGBALQ8YO6Xm6y4LgXWeiDez/4AD58bL6kAjJPhv6GcBICfFdDClGtxqTcMDvyGWqNOXnuRgqqbAompA+dQC8YBQ2RoNWytEUPPCbGnj47UBZ/azjvfJKc1tzqIRXzBD4rdac5BOvPYtDdNY2TQMWwKM4itnk4RviOtLtAkOZw0ICQBAgMBAAEwDQYJKoZIhvcNAQEFBQADgYEAT5lkoEg6Vw8041gcMgsfHxFI1/FLastFXz4ziPXBM5WDivRDhGfWwNcb7ViqPciEUeN7LflUyzr9s52tFlckRj8T2EIBvV7vtm4yLMYnWA1//a805SITtiDkyhpPPoU7e0hh7dFLCvIY+JwwhDcrV2Rnsz/ANIhVolVOMoo8XRo="

@Suppress("unused")
val spoofSignaturePatch = spoofSignaturePatch(
    fromPackageName = "com.sofascore.results",
    certificateBase64 = SOFASCORE_CERTIFICATE,
    compatibility = COMPATIBILITY_SOFASCORE,
    default = true,
    description = "Needed for match notifications and Google sign-in.",
)
