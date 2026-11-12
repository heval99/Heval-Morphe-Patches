package app.template.patches.crex.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

internal const val HOME_TABS = "Lin/cricketexchange/app/cricketexchange/home/model/HomeTabs;"
internal const val NAVIGATION_BAR_VIEW = "Lcom/google/android/material/navigation/NavigationBarView;"

// HomeActivity.onCreate (v26.10.07). Two anchors, both unobfuscated:
//  - NavigationBarView.getMenu(): followed by `move-result-object` and `iput-object` of the
//    Menu into a field (J1 in this version, name not relied on). The bottom nav is built here.
//  - "Default Videos": part of the launch-tab restore block, which compares the restored tab
//    name (kept in a String register) against HomeTabs.VIDEOS / MATCHES / HOMEV2.
internal object HomeCreateFingerprint : Fingerprint(
    definingClass = "Lin/cricketexchange/app/cricketexchange/activities/HomeActivity;",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(definingClass = NAVIGATION_BAR_VIEW, name = "getMenu"),
        string("Default Videos"),
    ),
)

internal const val HOME_CARDS_ADAPTER =
    "Lin/cricketexchange/app/cricketexchange/matches/HomeHomeTabFragmentNew\$HomeCardsAdapter;"

// HomeCardsAdapter.J() (v26.10.07): rebuilds the "For you" list's group-type list (a List<Integer>,
// field I in this version) on every getGroupCount(). It clears the list, adds one entry pair per
// series, then appends the fixed sections. Anchored on List.clear() followed by the "#CWTFV#" key.
internal object ForYouGroupListFingerprint : Fingerprint(
    definingClass = HOME_CARDS_ADAPTER,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Ljava/util/List;", name = "clear"),
        string("#CWTFV#"),
    ),
)

internal const val HOME_ACTIVITY = "Lin/cricketexchange/app/cricketexchange/activities/HomeActivity;"

// HomeActivity.oc() (v26.10.07): shows the red "NEW" label (TextView 0x7f0a1be0) over the bottom
// nav with a scale-in animation. Anchored on the pref it reads, "social_badge_intro_time".
internal object NewBadgeFingerprint : Fingerprint(
    definingClass = HOME_ACTIVITY,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("social_badge_intro_time"),
    ),
)

// HomeActivity.Eb(Boolean) (v26.10.07): sets the visibility of the red dot (view 0x7f0a0077) from a
// Boolean and the "is_home_visited" pref. Another method reads that pref too, but only this one
// takes a Boolean.
internal object RedDotFingerprint : Fingerprint(
    definingClass = HOME_ACTIVITY,
    returnType = "V",
    parameters = listOf("Ljava/lang/Boolean;"),
    filters = listOf(
        string("is_home_visited"),
    ),
)

internal const val LIVE_MATCH_ACTIVITY = "Lin/cricketexchange/app/cricketexchange/activities/LiveMatchActivity;"

// LiveMatchActivity.onCreate (v26.10.07). Reads the "is_discussions_enabled" pref and stores the
// result in a boolean field (c2 in this version) that gates the Discussions tab and the Discuss
// button. The patch finds that field from the first boolean store after the pref read.
internal object MatchDiscussionsFlagFingerprint : Fingerprint(
    definingClass = LIVE_MATCH_ACTIVITY,
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        string("is_discussions_enabled"),
    ),
)
