package app.template.patches.shazam.misc.openin

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string

// StreamingProvider data class (R8-renamed to Hn.u in 16.62.0). It holds one "Open in" provider
// from server config (streamingproviders[]: packagename + songuri/artisturi/albumuri), and every
// consumer (installed check, icon lookup, Intent.parseUri launch) reads its fields. The class is
// found through its toString(), whose prefix string is unique in the dex. It starts the
// concatenation, so it is matched by prefix: patcher 2.0 compares raw `strings` with the whole
// constant.
internal object StreamingProviderToStringFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(string("StreamingProvider(streamingProviderId=", StringComparisonType.STARTS_WITH)),
)

// Its only constructor: (streamingProviderId, providerName, packageName, songUri, artistUri,
// albumUri, isSyncable). Matched by shape inside the class found above.
internal object StreamingProviderConstructorFingerprint : Fingerprint(
    classFingerprint = StreamingProviderToStringFingerprint,
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "L",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
    ),
)
