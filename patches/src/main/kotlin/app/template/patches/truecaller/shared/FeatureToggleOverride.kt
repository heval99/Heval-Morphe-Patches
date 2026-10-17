package app.template.patches.truecaller.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.template.patches.shared.addInstructionsWithLabelsChecked

private const val FEATURE_STATE = "Lcom/truecaller/featuretoggles/FeatureState;"

// The central feature-toggle resolver isEnabled(key, default state) (edo.a in 26.39.6), called
// from over 500 classes. Two twin resolvers share the signature but read a different store or
// only the default; this one reads the toggle SharedPreferences.
internal object FeatureToggleResolverFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", FEATURE_STATE),
    filters = listOf(
        methodCall(definingClass = "Landroid/content/SharedPreferences;", name = "getBoolean"),
    ),
)

/**
 * Makes the feature-toggle resolver return [value] for the toggle [key], whatever the server or
 * the default state says. Each call adds a self-contained check at the start of the resolver, so
 * patches that override different keys work together in any order.
 */
internal fun BytecodePatchContext.overrideFeatureToggle(key: String, value: Boolean) {
    FeatureToggleResolverFingerprint.method.apply {
        // p1 is the key (compared null-safely); v0 is a local the method writes before reading.
        addInstructionsWithLabelsChecked(
            0,
            """
                const-string v0, "$key"
                invoke-virtual {v0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :resolve_toggle
                const/4 v0, ${if (value) "0x1" else "0x0"}
                return v0
            """,
            ExternalLabel("resolve_toggle", getInstruction(0)),
        )
    }
}
