package app.template.patches.football433.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.template.patches.shared.Constants.COMPATIBILITY_FOOTBALL433
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.addInstructionsWithLabelsChecked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val STRING = "Ljava/lang/String;"

/** The helper added to the AdInstanceManager: fails the ad with the given adId as NO_FILL. */
internal const val FAIL_LOAD_HELPER = "morpheFailLoad"

// LoadAdError.ERROR_CODE_NO_FILL and MobileAds.ERROR_DOMAIN, what the SDK reports for an empty slot.
private const val NO_FILL = "0x3"
private const val GMA_ERROR_DOMAIN = "com.google.android.gms.ads"
private const val BLOCKED_MESSAGE = "Ad blocked"

private val INVOKE_VIRTUAL = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/** The fingerprint's single match, or a loud failure naming every match. */
private fun BytecodePatchContext.single(fingerprint: Fingerprint, label: String): Match {
    val matches = fingerprint.matchAllOrNull().orEmpty()
    return matches.singleOrNull() ?: throw PatchException(
        "Disable ads: expected one $label, found " +
            matches.map { "${it.classDef.type}->${it.method.name}" },
    )
}

/** Registers of a non-static invoke, `this` first. */
private fun Instruction.invokeRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction ->
        listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun Instruction.methodRef(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

/**
 * The 433 Flutter app shows AdMob / Ad Manager ads only through the google_mobile_ads plugin:
 * Dart asks the plugin to create and load each banner, Ad Manager banner and rewarded
 * interstitial (the types this build's Dart code uses; the patch covers all nine plugin ad
 * types). Each plugin ad's load now reports a NO_FILL load failure to Dart instead of requesting
 * an ad, so the Dart widgets take their own "ad failed" path. No GMA or mediation (Liftoff /
 * Vungle) ad request is made. Rewarded ads fail to load like every other ad.
 * MobileAds.initialize is left alone (AGENTS.md rule 9).
 */
@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes banner, native, interstitial, rewarded and app-open ads.",
) {
    compatibleWith(COMPATIBILITY_FOOTBALL433)

    execute {
        // 1. The plugin's AdInstanceManager, FlutterAd and FlutterLoadAdError, by their event
        // strings and method shapes.
        val failMatch = single(AdFailedToLoadFingerprint, "AdInstanceManager.onAdFailedToLoad")
        val manager = failMatch.classDef.type
        val fail = failMatch.method
        val loadAdError = fail.parameterTypes[1].toString()

        val trackMatch = single(TrackAdFingerprint, "AdInstanceManager.trackAd")
        if (trackMatch.classDef.type != manager) {
            throw PatchException(
                "Disable ads: trackAd is on ${trackMatch.classDef.type}, onAdFailedToLoad on $manager",
            )
        }
        val track = trackMatch.method
        val flutterAd = track.parameterTypes[0].toString()

        // FlutterAd's only instance int field is the adId Dart knows the ad by.
        val adIdFields = classDefBy(flutterAd).fields.filter {
            it.type == "I" && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val adId = adIdFields.singleOrNull()
            ?: throw PatchException("Disable ads: expected one int adId field on $flutterAd, found ${adIdFields.map { it.name }}")

        // FlutterLoadAdError(int code, String domain, String message, FlutterResponseInfo info).
        // The plugin's codec writes the null info as a plain null, as for real SDK errors that
        // carry no ResponseInfo.
        val errorInits = classDefBy(loadAdError).methods.filter { method ->
            val parameters = method.parameterTypes.map(CharSequence::toString)
            method.name == "<init>" && parameters.size == 4 && parameters[0] == "I" &&
                parameters[1] == STRING && parameters[2] == STRING && parameters[3].startsWith("L")
        }
        val errorInit = errorInits.singleOrNull()
            ?: throw PatchException("Disable ads: expected one (int, String, String, info) constructor on $loadAdError, found ${errorInits.size}")
        val errorInitSignature = errorInit.parameterTypes.joinToString("")

        // 2. A helper on the manager builds the NO_FILL error and hands it to onAdFailedToLoad,
        // which posts the Dart event on the main looper like a real load failure.
        // v0 = error, v1 = code, v2 = domain, v3 = message, v4 = null info, p0 (v5), p1 (v6) = adId.
        mutableClassDefBy(manager).methods.add(
            ImmutableMethod(
                manager,
                FAIL_LOAD_HELPER,
                listOf(ImmutableMethodParameter("I", null, null)),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(7),
            ).toMutable().apply {
                addInstructionsChecked(
                    0,
                    """
                        new-instance v0, $loadAdError
                        const/4 v1, $NO_FILL
                        const-string v2, "$GMA_ERROR_DOMAIN"
                        const-string v3, "$BLOCKED_MESSAGE"
                        const/4 v4, 0x0
                        invoke-direct/range {v0 .. v4}, $loadAdError-><init>($errorInitSignature)V
                        invoke-virtual {p0, p1, v0}, $manager->${fail.name}(I$loadAdError)V
                        return-void
                    """.trimIndent(),
                )
            },
        )

        // 3. The load methods: in onMethodCall every load case calls trackAd(ad, adId) and then
        // the ad's no-arg load method (banner, Ad Manager banner and fluid share one).
        val onMethodCall = single(PluginMethodCallFingerprint, "GoogleMobileAdsPlugin.onMethodCall").method
        val insns = onMethodCall.implementation!!.instructions.toList()
        val loadCalls = insns.indices.mapNotNull { index ->
            val ref = insns[index].methodRef() ?: return@mapNotNull null
            if (insns[index].opcode !in INVOKE_VIRTUAL || ref.definingClass != manager || ref.name != track.name ||
                ref.parameterTypes.map(CharSequence::toString) != track.parameterTypes.map(CharSequence::toString)
            ) return@mapNotNull null
            val adRegister = insns[index].invokeRegisters()[1]
            val next = (index + 1 until insns.size).firstOrNull { insns[it].methodRef() != null }
                ?.let { insns[it] }
            val load = next?.methodRef()
            if (next == null || load == null || next.opcode !in INVOKE_VIRTUAL || load.returnType != "V" ||
                load.parameterTypes.isNotEmpty() || next.invokeRegisters() != listOf(adRegister)
            ) {
                throw PatchException("Disable ads: trackAd at $index in onMethodCall is not followed by a no-arg load on the ad; found $load")
            }
            load
        }
        if (loadCalls.size != LOAD_METHOD_NAMES.size) {
            throw PatchException(
                "Disable ads: expected ${LOAD_METHOD_NAMES.size} trackAd + load calls in onMethodCall " +
                    "(one per ${LOAD_METHOD_NAMES.joinToString()}), found ${loadCalls.size}",
            )
        }

        // Resolve each call to the class that defines the load (R8 may type a call by a subclass).
        val superclasses = mutableMapOf<String, String?>()
        classDefForEach { superclasses[it.type] = it.superclass }
        fun chain(type: String) = generateSequence(type) { superclasses[it] }.takeWhile { it != flutterAd }
        fun isFlutterAd(type: String) = generateSequence(type) { superclasses[it] }.any { it == flutterAd }

        val loads = loadCalls.map { ref ->
            if (!isFlutterAd(ref.definingClass)) {
                throw PatchException("Disable ads: load call ${ref.definingClass}->${ref.name} is not on a FlutterAd ($flutterAd)")
            }
            val owner = chain(ref.definingClass).firstOrNull { type ->
                classDefBy(type).methods.any {
                    it.name == ref.name && it.parameterTypes.isEmpty() && it.returnType == "V" && it.implementation != null
                }
            } ?: throw PatchException("Disable ads: no concrete ${ref.definingClass}->${ref.name}()V")
            owner to ref.name
        }.distinct()

        // Every concrete FlutterAd subclass must reach a patched load (itself or a superclass).
        val adClasses = superclasses.keys.filter { type ->
            type != flutterAd && isFlutterAd(type) && !AccessFlags.ABSTRACT.isSet(classDefBy(type).accessFlags)
        }
        val uncovered = adClasses.filter { type -> chain(type).none { owner -> loads.any { it.first == owner } } }
        if (adClasses.size != LOAD_METHOD_NAMES.size || uncovered.isNotEmpty()) {
            throw PatchException(
                "Disable ads: expected ${LOAD_METHOD_NAMES.size} concrete FlutterAd classes, found ${adClasses.size} " +
                    "($adClasses); without a patched load: $uncovered",
            )
        }

        // 4. Each load fails the ad and returns before it builds an ad view or calls the SDK.
        // v0 = manager, v1 = adId; a null manager returns quietly, as the plugin's own checks do.
        loads.forEach { (owner, name) ->
            val ownerClass = mutableClassDefBy(owner)
            val managerFields = chain(owner).flatMap { type ->
                classDefBy(type).fields.filter { it.type == manager && !AccessFlags.STATIC.isSet(it.accessFlags) }
                    .map { type to it.name }
            }.toList()
            val (fieldOwner, managerField) = managerFields.singleOrNull()
                ?: throw PatchException("Disable ads: expected one $manager field on $owner, found $managerFields")
            val method = ownerClass.methods.single {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" && it.implementation != null
            }
            val locals = method.implementation!!.registerCount - 1
            if (locals < 2) throw PatchException("Disable ads: $owner->$name()V has only $locals locals")
            method.addInstructionsWithLabelsChecked(
                0,
                """
                    iget-object v0, p0, $fieldOwner->$managerField:$manager
                    if-eqz v0, :blocked
                    iget v1, p0, $flutterAd->${adId.name}:I
                    invoke-virtual {v0, v1}, $manager->$FAIL_LOAD_HELPER(I)V
                    :blocked
                    return-void
                """.trimIndent(),
            )
        }
    }
}
