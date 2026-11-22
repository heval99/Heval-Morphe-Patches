package app.template.patches.sofascore.spoiler

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

// Sofascore's push receiver: FCMListenerService keeps its name (it is a manifest service) and the
// method is the FirebaseMessagingService override, so both names are stable. It parses the data
// key "notification" (JSON) and posts the notification, the widget refresh and the TTS readout.
internal object FcmMessageReceivedFingerprint : Fingerprint(
    definingClass = "Lcom/sofascore/results/firebase/push/FCMListenerService;",
    name = "onMessageReceived",
    returnType = "V",
    parameters = listOf("Lcom/google/firebase/messaging/RemoteMessage;"),
)

// The Compose match row model (26.11.09: ji7): toString is generated from the data class, so its
// constant starts with the class's source name. Its only constructor is the hook site; the model's
// field types (RowText, EventTeamLine) are read from that constructor, never named here.
internal object EventRowModelToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(string("EventRowModel(eventId=", StringComparisonType.STARTS_WITH)),
)

// The mapper that builds EventRowModel from an Event (26.11.09: mi7.m(IILjava/lang/Object;)Object).
// Its shape plus three kept model calls make it unique; the only other EventRowModel constructor
// caller copies a finished row and has a different shape.
internal object EventRowModelMapperFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("I", "I", "Ljava/lang/Object;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/sofascore/model/mvvm/model/Event;", name = "getHomeTeamSeed\$default"),
        methodCall(definingClass = "Lcom/sofascore/model/mvvm/model/Event;", name = "getStreamContentGeoRestrictions"),
        methodCall(definingClass = "Lcom/sofascore/model/mvvm/model/FootballEvent;", name = "isVarInProgress\$default"),
    ),
)
