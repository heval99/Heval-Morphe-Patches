package app.template.patches.flashscore.misc.notifications

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.flashscore.controls.FlashscoreControlsCategory
import app.template.patches.flashscore.controls.flashscoreControls
import app.template.patches.shared.Constants.COMPATIBILITY_FLASHSCORE
import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.addHevalGate

private const val HANDLER_INFO = "Leu/livesport/notification/handler/HandlerInfo;"

internal val BLOCK_MARKETING_NOTIFICATIONS = HevalFeature(
    id = "flashscore.block_marketing_notifications",
    name = "Block marketing notifications",
    description = "Drops promotional \"INFO\" pushes. Match, news and race alerts are kept.",
    category = FlashscoreControlsCategory.NOTIFICATIONS,
    default = false,
    restartRequired = false,
)

/**
 * Flashscore's push dispatcher picks a handler by the notification's type string
 * (`eu.livesport.notification.handler.*`): FS_NEWS for breaking news, EVENT_CHANGE for match
 * changes, OPEN_RACE_STAGE for race stages, NEWS_ARTICLE for news articles, USER_DATA_SYNC for the
 * account sync and INFO for the generic promotional push (it carries only a URL and a big picture).
 *
 * `HandlerInfo.process` is the only place that posts the INFO notification, so the switch makes it
 * return at once while it is on; the dispatcher then has nothing to show. Every other type is
 * untouched, and with the switch off the stock body runs.
 */
@Suppress("unused")
val blockMarketingNotificationsPatch = bytecodePatch(
    name = "Block marketing notifications",
    description = "Drops promotional push notifications.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_FLASHSCORE)

    dependsOn(flashscoreControls.patch)

    execute {
        val handler = mutableClassDefBy(HANDLER_INFO)
        val process = handler.methods.singleOrNull {
            it.name == "process" && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Llth;")
        } ?: throw PatchException(
            "$HANDLER_INFO.process(Context, lth) not found: " +
                handler.methods.filter { it.name == "process" }.joinToString { it.parameterTypes.joinToString() },
        )

        process.addHevalGate(BLOCK_MARKETING_NOTIFICATIONS.id) { _ -> "return-void" }
        flashscoreControls.register(BLOCK_MARKETING_NOTIFICATIONS)
    }
}
