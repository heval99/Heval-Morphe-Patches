package app.template.patches.truecaller.misc.nags

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.featurePatch
import app.template.patches.shared.hevalcontrols.hevalReturnEarly
import app.template.patches.truecaller.controls.TruecallerControlsCategory
import app.template.patches.truecaller.controls.truecallerControls
import app.template.patches.truecaller.shared.overrideReadFeatureToggles
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FEATURE = "truecaller.disable_nags"

// A Heval Controls feature: the overrides apply only while it is on.
@Suppress("unused")
val disableNagsPatch = truecallerControls.featurePatch(
    HevalFeature(
        id = FEATURE,
        name = "Disable nags",
        description = "Disables default dialer, default caller ID, permission, survey and profile reminder prompts.",
        category = TruecallerControlsCategory.PROMOTIONS,
    ),
) {
    // Each toggle is read through the toggle resolver and now reads as off. The patch fails if
    // a key is no longer read there.
    // - "featureDefaultDialerInterstitial" / "featureDefaultDialerPermanentSpace": the
    //   set-as-default-dialer interstitial and its permanent slot.
    // - "featureNotificationsPermissionBanner" (3 reads), "featureDOOABanner" (display over
    //   other apps) and "featureDisableBatteryOptimizationBanner": the permission banners.
    // - "featurePeriodicallyCheckPermissions": the periodic permission re-check prompts.
    // - "featureRatingsAndReviewsSurveys", "featureBlockSurveyFlow" and "featureFeedbackSurvey"
    //   (Family Protection): the surveys.
    // - "featureInvalidProfileNotification": the invalid-profile reminder worker finishes
    //   without posting its notification.
    // "featureReduceValueForNonDd" is left alone, and so are "featureUnreadRemindersEmail" and
    // "featurePremiumReward": reading those as off turns a nag on instead.
    overrideReadFeatureToggles(
        listOf(
            "featureDefaultDialerInterstitial",
            "featureDefaultDialerPermanentSpace",
            "featureNotificationsPermissionBanner",
            "featureDOOABanner",
            "featureDisableBatteryOptimizationBanner",
            "featurePeriodicallyCheckPermissions",
            "featureRatingsAndReviewsSurveys",
            "featureBlockSurveyFlow",
            "featureFeedbackSurvey",
            "featureInvalidProfileNotification",
        ),
        false,
        FEATURE,
    )

    // The Settings banner "Set Truecaller as your default caller ID app" (issue #166, 2026-11-26) is
    // not behind a toggle: the banner picker asks a ()Z check (the first call before the CALLER_ID
    // constant, a method of the picker's own class) and answers CALLER_ID when it is true. The check
    // answers false, so the picker falls through to the default dialer, draw over other apps and
    // battery banners, which stay stock; the Settings state builder asks the same check and agrees.
    // Role state and call handling are untouched.
    val picker = CallerIdBannerPickerFingerprint
    val check = (picker.method.getInstruction<ReferenceInstruction>(picker.instructionMatches.first().index).reference as MethodReference)
    val checkMethod = mutableClassDefBy(check.definingClass).methods.singleOrNull { method ->
        method.name == check.name && method.returnType == "Z" && method.parameterTypes.isEmpty()
    } ?: throw PatchException("${check.definingClass} has no ${check.name}()Z")
    if (checkMethod.implementation == null || AccessFlags.STATIC.isSet(checkMethod.accessFlags)) {
        throw PatchException("${check.definingClass}->${checkMethod.name} is not a concrete instance method")
    }
    checkMethod.hevalReturnEarly(FEATURE, false)
}
