package app.template.patches.eurosport.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.template.patches.eurosport.shared.toStringFingerprint

// Storyteller (the Stories / Clips SDK) keeps its public class and member names.
internal const val STORYTELLER_SETTINGS = "Lcom/storyteller/domain/settings/entities/Settings;"
internal const val STORYTELLER_CLIPS_AD_CONFIGURATION =
    "Lcom/storyteller/Storyteller\$StorytellerClipsAdConfiguration;"
internal const val STORYTELLER_COMPANION = "Lcom/storyteller/Storyteller\$Companion;"
internal const val STORYTELLER_MODULE = "Lcom/storyteller/domain/ads/ports/StorytellerModule;"
internal const val STORYTELLER_ADS_MODULES = "Lcom/storyteller/modules/ads/"

// Teads keeps its SDK class names.
internal const val TEADS_AD_PLACEMENT_MEDIA = "Ltv/teads/sdk/combinedsdk/adplacement/TeadsAdPlacementMedia;"

// The ad slot model the feed and article builders add (8.33.0: ze, two ad-unit configs).
internal val AdPlaceholderToStringFingerprint = toStringFingerprint("AdPlaceholderModel(content=")

// The method that turns the server's ad provider into an ad unit config (8.33.0: pjg.o). It sits
// in the same class as the AdPlaceholderModel factory, which is cross-checked against it.
internal object UnsupportedAdProviderFingerprint : Fingerprint(
    filters = listOf(string("Unsupported provider: ", StringComparisonType.STARTS_WITH)),
)

// The video player model (8.33.0: yrb). Its toString() names the showAds flag, which decides
// whether the player gets the ad (IMA) parameters.
internal val PlayerModelToStringFingerprint = toStringFingerprint("PlayerModel(isLive=", "showAds=")
