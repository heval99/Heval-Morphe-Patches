package app.template.patches.brave.misc.background

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_BRAVE_EXPERIMENTAL
import app.template.patches.shared.addInstructionsChecked

private const val SCHEDULER = "Lorg/chromium/chrome/browser/background_sync/BackgroundSyncBackgroundTaskScheduler;"

@Suppress("unused")
val stopBackgroundWakeupsPatch = bytecodePatch(
    name = "Stop background sync wakeups",
    description = "Stops websites from waking Brave to sync in the background, so offline sync waits until you open them.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_BRAVE_EXPERIMENTAL)

    execute {
        // The scheduler is a JNI-called class with kept names. scheduleOneOffTask reports false
        // (no job scheduled) and clears any job already queued for both sync types, so a stale
        // job from before the patch cannot wake the app either.
        Fingerprint(
            definingClass = SCHEDULER,
            name = "scheduleOneOffTask",
            returnType = "Z",
            parameters = listOf("I", "J"),
        ).method.addInstructionsChecked(
            0,
            """
                const/4 v0, 0x0
                invoke-virtual {p0, v0}, $SCHEDULER->cancelOneOffTask(I)V
                const/4 v0, 0x1
                invoke-virtual {p0, v0}, $SCHEDULER->cancelOneOffTask(I)V
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}
