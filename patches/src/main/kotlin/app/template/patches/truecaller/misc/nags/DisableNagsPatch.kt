package app.template.patches.truecaller.misc.nags

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.featurePatch
import app.template.patches.shared.hevalcontrols.hevalReturnEarly
import app.template.patches.truecaller.controls.TruecallerControlsCategory
import app.template.patches.truecaller.controls.truecallerControls
import app.template.patches.truecaller.shared.overrideReadFeatureToggles
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val FEATURE = "truecaller.disable_nags"

// A Heval Controls feature: the overrides apply only while it is on.
@Suppress("unused")
val disableNagsPatch = truecallerControls.featurePatch(
    HevalFeature(
        id = FEATURE,
        name = "Disable nags",
        description = "Hides default app, permission, survey and profile reminders and the call log promo cards.",
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

    // The call-log promo cards "Missed Call Notifications" and "Ring silent" (the Do Not Disturb access
    // request) are chosen by a cooldown read: const-string "<key>", then a (String)Z call on the settings
    // object (true = the cooldown has elapsed, show the card), move-result. The same keys are also
    // written by (String)V calls (the dismiss side), which are left alone. Every read has its result
    // passed through NagPromos.hideCooldownPromo(Z)Z, which answers false while this feature is on, so
    // the card is skipped and the picker moves on to the next candidate (issue #153). Sites by method
    // shape, not name: the legacy call-log picker (two reads) and the promo banner framework (one read).
    // Collect every read first and check the expected set before the first edit.
    val promoClasses = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.methods.any { method -> method.implementation?.instructions?.any { it.promoKey() != null } == true }) {
            promoClasses += classDef
        }
    }
    class PromoRead(val method: MutableMethod, val moveResult: Int, val register: Int, val key: String)
    val reads = mutableListOf<PromoRead>()
    for (classDef in promoClasses) {
        for (method in mutableClassDefBy(classDef).methods) {
            val instructions = method.implementation?.instructions?.toList() ?: continue
            instructions.indices.forEach { index ->
                val key = instructions[index].promoKey() ?: return@forEach
                val keyRegister = (instructions[index] as OneRegisterInstruction).registerA
                val call = instructions.getOrNull(index + 1) as? FiveRegisterInstruction
                val reference = (call as? ReferenceInstruction)?.reference as? MethodReference
                // Writers are (String)V; the read is (String)Z with the key as the argument.
                if (call == null || reference == null || call.opcode != Opcode.INVOKE_VIRTUAL || reference.returnType != "Z" ||
                    reference.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/String;") ||
                    call.registerCount != 2 || call.registerD != keyRegister
                ) return@forEach
                val result = instructions.getOrNull(index + 2)
                if (result?.opcode != Opcode.MOVE_RESULT) throw PatchException("${method.definingClass}->${method.name}: no move-result after the $key read")
                reads += PromoRead(method, index + 2, (result as OneRegisterInstruction).registerA, key)
            }
        }
    }
    val counts = reads.groupingBy { it.key }.eachCount()
    if (counts != mapOf(MISSED_CALL_PROMO_KEY to 2, DND_PROMO_KEY to 1)) {
        throw PatchException("Promo cooldown reads not as expected (missed-call 2, do-not-disturb 1): $counts")
    }
    // Last read first within a method: an insert shifts the indices after it.
    for (read in reads.sortedByDescending { it.moveResult }) {
        read.method.addInstructionsChecked(
            read.moveResult + 1,
            """
                invoke-static/range { v${read.register} .. v${read.register} }, $NAG_PROMOS->hideCooldownPromo(Z)Z
                move-result v${read.register}
            """.trimIndent(),
        )
    }
}
private const val NAG_PROMOS = "Lapp/template/extension/truecaller/NagPromos;"
private const val MISSED_CALL_PROMO_KEY = "key_missed_call_notif_promo_last_time"
private const val DND_PROMO_KEY = "key_dnd_promo_last_time"

private fun Instruction.promoKey(): String? {
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) return null
    val text = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
    return text?.takeIf { it == MISSED_CALL_PROMO_KEY || it == DND_PROMO_KEY }
}
