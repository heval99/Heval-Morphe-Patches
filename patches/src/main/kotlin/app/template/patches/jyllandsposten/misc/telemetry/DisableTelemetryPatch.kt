package app.template.patches.jyllandsposten.misc.telemetry

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_JYLLANDSPOSTEN
import app.template.patches.shared.telemetry.disableGemiusAudience
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

// The JPPol Snowplow tracker is built for this endpoint; the wrapper class is R8-renamed, the
// endpoint string is not.
private const val SNOWPLOW_ENDPOINT = "https://c.jyllands-posten.dk"

/**
 * Turns off Firebase Analytics/Crashlytics/Sessions/Performance and ad-ID collection through the
 * manifest switches.
 */
private val disableTelemetryManifestPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_JYLLANDSPOSTEN)

    execute {
        document("AndroidManifest.xml").use { doc ->
            val applications = doc.getElementsByTagName("application")
            if (applications.length == 0) throw PatchException("AndroidManifest.xml has no <application>")
            val application = applications.item(0) as Element

            val existing = buildMap {
                val metas = application.getElementsByTagName("meta-data")
                for (i in 0 until metas.length) {
                    val meta = metas.item(i) as? Element ?: continue
                    if (meta.parentNode != application) continue
                    put(meta.getAttribute("android:name"), meta)
                }
            }

            for ((name, value) in mapOf(
                "firebase_analytics_collection_enabled" to "false",
                "firebase_analytics_collection_deactivated" to "true",
                "firebase_crashlytics_collection_enabled" to "false",
                "firebase_performance_collection_deactivated" to "true",
                "firebase_sessions_enabled" to "false",
                "google_analytics_adid_collection_enabled" to "false",
            )) {
                val meta = existing[name] ?: doc.createElement("meta-data").also {
                    it.setAttribute("android:name", name)
                    application.appendChild(it)
                }
                meta.setAttribute("android:value", value)
            }
        }
    }
}

/**
 * Jyllands-Posten is built on the shared JP/Politikens platform. Its telemetry is Gemius audience
 * measurement (three sendEvent() overrides), the JPPol Snowplow tracker and Firebase Crashlytics.
 *
 * The Gemius events are no-opped by the shared helper. The Snowplow wrapper is found by the tracker
 * endpoint it builds; its only non-static app-typed field is the tracker, and both event methods
 * call the tracker's single void (payload) method, which returns at once. Firebase is switched off
 * at the manifest. AdOcean ads and the web paywall are separate and not part of this patch.
 */
@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables Gemius audience measurement, Snowplow events and Firebase crash reporting.",
) {
    compatibleWith(COMPATIBILITY_JYLLANDSPOSTEN)

    dependsOn(disableTelemetryManifestPatch)

    execute {
        disableGemiusAudience()

        val wrapper = classDefByStrings(SNOWPLOW_ENDPOINT).singleOrNull()
            ?: throw PatchException("Snowplow wrapper ($SNOWPLOW_ENDPOINT) not found")
        val trackerType = wrapper.fields.singleOrNull { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && classDefByOrNull(field.type) != null
        }?.type ?: throw PatchException("${wrapper.type} has no Snowplow tracker field")
        val trackCall = wrapper.methods.asSequence()
            .flatMap { method -> method.implementation?.instructions?.asSequence() ?: emptySequence() }
            .mapNotNull { insn -> (insn as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull { call ->
                call.definingClass == trackerType && call.returnType == "V" && call.parameterTypes.size == 1
            } ?: throw PatchException("${wrapper.type} never calls a Snowplow track method")
        val emit = mutableClassDefBy(trackerType).methods.singleOrNull { method ->
            method.name == trackCall.name && method.returnType == "V" && method.implementation != null &&
                method.parameterTypes.map(CharSequence::toString) == trackCall.parameterTypes.map(CharSequence::toString)
        } ?: throw PatchException("$trackerType.${trackCall.name} not found")
        emit.returnEarly()
    }
}
