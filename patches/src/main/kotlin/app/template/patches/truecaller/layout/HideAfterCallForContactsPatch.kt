package app.template.patches.truecaller.layout

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.addHevalGate
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import app.template.patches.shared.hevalcontrols.featurePatch
import app.template.patches.truecaller.controls.TruecallerControlsCategory
import app.template.patches.truecaller.controls.truecallerControls
import app.template.patches.truecaller.shared.overrideReadFeatureToggles

private const val FEATURE = "truecaller.hide_after_call_contacts"

// A Heval Controls feature: the overrides apply only while it is on.
@Suppress("unused")
val hideAfterCallForContactsPatch = truecallerControls.featurePatch(
    HevalFeature(
        id = FEATURE,
        name = "Hide after-call screen for contacts",
        description = "Hides the after-call screen for saved contacts.",
        category = TruecallerControlsCategory.CONTACTS,
    ),
    default = false,
) {
    // The after-call rules show the screen for a phonebook contact only when the user's
    // "afterCallForPbContacts" setting AND "featureShowACSAllIncoming" (incoming) or
    // "featureShowACSAllOutgoing" (outgoing) are on. Both toggles now read as off, so
    // contacts never get it. Missed calls, unknown numbers and the user's settings
    // ("afterCall", "afterCallForPbContacts", "afterCallForNonPbContacts") are untouched.
    overrideReadFeatureToggles(
        listOf("featureShowACSAllIncoming", "featureShowACSAllOutgoing"),
        false,
        FEATURE,
    )

    // The server-driven FullscreenAcsConfig (Firebase "fullscreenAcsConfig_26137", kept class and getter
    // names) has its own answer for a phonebook contact on a non-missed call; the callers act on it
    // and never reach the rules above unless it is DO_NOTHING (issue #181). Each of the seven
    // phonebook getters now answers DO_NOTHING while the feature is on; the *PbMissed getters
    // and the non-phonebook getters stay stock. The getter has no free register, so its result goes
    // through a static helper on the config class.
    val config = mutableClassDefBy(FULLSCREEN_ACS_CONFIG)
    val helper = config.addFacsBehaviorHelper()
    val getters = config.methods.filter { it.name in PB_GETTERS }
    if (getters.map { it.name }.sorted() != PB_GETTERS.sorted()) {
        throw PatchException("FullscreenAcsConfig phonebook getters not found exactly once: ${getters.map { it.name }}")
    }
    for (getter in getters) {
        if (getter.returnType != FACS_BEHAVIOR || getter.parameterTypes.isNotEmpty() || getter.implementation == null) {
            throw PatchException("Unexpected FullscreenAcsConfig getter shape: $getter")
        }
        // Instruction 1 is the getter's return-object; p0 holds the field value there.
        val last = getter.implementation!!.instructions.size - 1
        getter.addInstructionsChecked(
            last,
            """
                invoke-static/range { p0 .. p0 }, $helper
                move-result-object p0
            """,
        )
    }
}

private const val FULLSCREEN_ACS_CONFIG = "Lcom/truecaller/incallui/utils/FullscreenAcsConfig;"
private const val FACS_BEHAVIOR = "Lcom/truecaller/acs/util/FacsBehavior;"

// Phonebook-contact behaviours of calls that were answered, dismissed or placed; the *PbMissed ones
// (missed calls show the screen by design, issue #156) are left out on purpose.
private val PB_GETTERS = listOf(
    "getUnlockedPbIncomingAccept", "getUnlockedPbIncomingDismiss", "getUnlockedPbIncomingDismissNotification",
    "getUnlockedPbOutgoing", "getLockedPbIncomingAccept", "getLockedPbIncomingDismiss", "getLockedPbOutgoing",
)

private const val HELPER_NAME = "morpheHideAfterCallForContacts"

/**
 * Adds `static (FacsBehavior)FacsBehavior`: DO_NOTHING while the feature is on, else the argument.
 */
private fun MutableClass.addFacsBehaviorHelper(): MethodReference {
    val reference = ImmutableMethodReference(type, HELPER_NAME, listOf(FACS_BEHAVIOR), FACS_BEHAVIOR)
    val helper = ImmutableMethod(
        type, HELPER_NAME, listOf(ImmutableMethodParameter(FACS_BEHAVIOR, null, null)), FACS_BEHAVIOR,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
        null, null, MutableMethodImplementation(2),
    ).toMutable()
    helper.addInstructionsChecked(0, "return-object p0")
    helper.addHevalGate(FEATURE) { "sget-object p0, $FACS_BEHAVIOR->DO_NOTHING:$FACS_BEHAVIOR" }
    methods.add(helper)
    return reference
}
