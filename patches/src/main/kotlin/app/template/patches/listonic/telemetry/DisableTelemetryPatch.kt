package app.template.patches.listonic.telemetry

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_LISTONIC
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.misc.telemetry.forceFirebaseManifestSwitches

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Turns off analytics, crash reporting and tracking."
) {
    compatibleWith(COMPATIBILITY_LISTONIC)

    execute {
        // 1. Firebase Analytics, Crashlytics, Sessions and Performance, as the values the
        // SDKs read from the manifest. Push (Messaging) and the init provider are left alone.
        forceFirebaseManifestSwitches(app = "Listonic", analytics = true, crashlytics = true, sessions = true, performance = true)

        // 2. TikTok Business SDK wrapper: init, start tracking, events and consent.
        listOf(tikTokStart, tikTokBeginTracking, tikTokTrack, tikTokSetConsent).forEach { it.method.returnEarly() }

        // 3. AdShift attribution SDK: never initialised, started or fed app links; the device id
        // is null, which its only caller checks for.
        listOf(adShiftInitialize, adShiftStart, adShiftStop, adShiftHandleAppLinkIntent).forEach { it.method.returnEarly() }
        adShiftDeviceId.method.addInstructionsChecked(
            0,
            """
                const/4 p0, 0x0
                return-object p0
            """,
        )

        // 4. Microsoft Clarity never starts; the two calls the app makes without it are no-ops.
        for (initialize in listOf(clarityInitializeContext, clarityInitializeActivity)) {
            initialize.method.addInstructionsChecked(
                0,
                """
                    sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                    return-object v0
                """,
            )
        }
        claritySendCustomEvent.method.returnEarly(false)
        claritySetCurrentScreenName.method.addInstructionsChecked(
            0,
            """
                sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object v0
            """,
        )

        // 5. Gemius audience SDK: no init and no events.
        gemiusInit.method.returnEarly()
        gemiusSendEvent.method.returnEarly()

        // 6. Facebook SDK (login only): no automatic app events and no advertising id.
        facebookAutoLogEvents.method.returnEarly(false)
        facebookAdvertiserId.method.returnEarly(false)
    }
}
