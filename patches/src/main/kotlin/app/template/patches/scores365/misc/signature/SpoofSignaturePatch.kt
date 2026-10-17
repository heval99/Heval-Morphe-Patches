package app.template.patches.scores365.misc.signature

import app.template.patches.shared.Constants.COMPATIBILITY_365SCORES
import app.template.patches.shared.misc.signature.spoofSignaturePatch

// The Play Store signing certificate (CN=Roe Aharoni, OU=Development, O=365 Scores, SHA-1
// 47f994bd2eac89f4aeea0aa1d95cb3ba3ec3f1a1), DER as base64, read from the v2/v3 signing block of
// the APKMirror 14.9.6 and 14.9.7 bundles. 365Scores' Firebase API key only accepts this certificate:
// re-signed, Firebase Installations fails with 403 API_KEY_ANDROID_APP_BLOCKED, so FCM never
// gets a token and match alerts never arrive.
internal const val SCORES365_CERTIFICATE =
    "MIICWzCCAcSgAwIBAgIEUKJT8DANBgkqhkiG9w0BAQUFADByMQswCQYDVQQGEwJJTDEPMA0GA1UECBMGQ0VOVEVSMREwDwYDVQQHEwhUZWwgQXZpdjETMBEGA1UEChMKMzY1IFNjb3JlczEUMBIGA1UECxMLRGV2ZWxvcG1lbnQxFDASBgNVBAMTC1JvZSBBaGFyb25pMB4XDTEyMTExMzE0MDY0MFoXDTM3MTEwNzE0MDY0MFowcjELMAkGA1UEBhMCSUwxDzANBgNVBAgTBkNFTlRFUjERMA8GA1UEBxMIVGVsIEF2aXYxEzARBgNVBAoTCjM2NSBTY29yZXMxFDASBgNVBAsTC0RldmVsb3BtZW50MRQwEgYDVQQDEwtSb2UgQWhhcm9uaTCBnzANBgkqhkiG9w0BAQEFAAOBjQAwgYkCgYEAx1HnUT7DfT0Un8252UoLmfoJR9WGP0rpw51f0GR3cSJ6wwrRGRjd6/thxfHQZ8hx3D0rHuaGr+BkUeltR5we9JhV/zD8a7veAUE8FKdmZ6rn6KYQemU/ycfKZi13JVaKBG4ls8Xp+AavbQiNYcOP6+0pZG4cUpqHZW4CZiFYznsCAwEAATANBgkqhkiG9w0BAQUFAAOBgQACA209FfdtmRFGq5O+nTPwePVa0QbQF58LSOu+fCjsRKcFqSjahU4wfs4Uli0U2IjSvIi7LWRjb8VyA6FbB6teCACBfNbBht646ZwHmp5DvSfTnkucqkdDcgGywclrwzSHa8Qmk7ZYJvcjE2/FGeuhLtFkEc8edqNMO0XCq4s1hQ=="

@Suppress("unused")
val spoofSignaturePatch = spoofSignaturePatch(
    fromPackageName = "com.scores365",
    certificateBase64 = SCORES365_CERTIFICATE,
    compatibility = COMPATIBILITY_365SCORES,
)
