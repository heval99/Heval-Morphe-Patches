package app.template.patches.sofascore.misc.facebook

import app.morphe.patcher.Fingerprint

// A ContentProvider declared in the manifest, so the class name and the framework onCreate
// override survive R8. It is the Audience Network auto-init entry point that runs before
// Application.onCreate(). (FacebookInitProvider must keep running, see DisableFacebookSdkPatch.)
object AudienceNetworkContentProviderFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/ads/AudienceNetworkContentProvider;",
    name = "onCreate",
)
