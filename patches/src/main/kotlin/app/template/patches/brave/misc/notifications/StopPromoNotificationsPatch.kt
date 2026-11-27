package app.template.patches.brave.misc.notifications

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL

// "L" matches any object type: R8 narrows the Context parameter on some builds (see Constants.kt).
private const val OBJECT = "L"

@Suppress("unused")
val stopPromoNotificationsPatch = bytecodePatch(
    name = "Stop promo notifications",
    description = "Stops Brave's reminder notifications and the notification opt-in prompt.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    execute {
        // ── 1. Retention reminders: never schedule the alarms ──────────────────────────────
        //  The scheduler class is obfuscated; its three entry points are matched by their
        //  shape and the kept preference/extra strings. "Hide promotional prompts" already stops
        //  the notification from being posted; this stops the alarm from waking the app at all.
        Fingerprint(
            returnType = "V",
            parameters = listOf(OBJECT),
            strings = listOf(string("dormant_users_days_14")),
        ).method.returnEarly()

        Fingerprint(
            returnType = "V",
            parameters = listOf(OBJECT, "Ljava/lang/String;"),
            strings = listOf(string("notification_type")),
        ).method.returnEarly()

        Fingerprint(
            returnType = "V",
            parameters = listOf(OBJECT, "Ljava/lang/String;", "J"),
            strings = listOf(string("notification_type")),
        ).method.returnEarly()

        // ── 2. "Turn on tips notifications" opt-in prompt ──────────────────────────────────
        //  Callback.onResult of the permission check that decides whether to show the promo.
        Fingerprint(
            name = "onResult",
            returnType = "V",
            parameters = listOf("Ljava/lang/Object;"),
            strings = listOf(string("Chrome.TipsNotifications.OptInShowCount")),
        ).method.returnEarly()
    }
}

