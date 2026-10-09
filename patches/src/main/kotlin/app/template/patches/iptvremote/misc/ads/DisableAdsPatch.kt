package app.template.patches.iptvremote.misc.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_IPTVREMOTE
import com.android.tools.smali.dexlib2.AccessFlags

private const val WORTISE_SDK = "Lcom/wortise/ads/WortiseSdk;"
private const val CONTEXT = "Landroid/content/Context;"

/**
 * Turns off the app's ad mediation layer (issue #35). Yandex banners/interstitials/instream
 * load through the app's own provider without Wortise, so killing WortiseSdk.initialize alone
 * left them running.
 *
 * 1. Provider settings (interstitial helper, instream devices/mode/page id, waterfall, network
 *    list, flags) delegate to the app's built-in no-ads sibling provider.
 * 2. The placement view factory is NOT delegated: the sibling's version wraps an empty list in
 *    a composite whose constructor throws, which crashed launch via the banner fragment's
 *    onAttach. Instead its callers are cut off: the banner gate (3) and the consent gate (4).
 * 3. The provider's static "banners allowed" check (false on Android TV) returns false, so no
 *    banner fragment is ever added, the same path the app takes on TV.
 * 4. The consent gate drops deferred ad actions, so the idle placement preloader, interstitial
 *    loaders and instream preload never run (and the native fullscreen interstitial, which
 *    only shows a preloaded placement, never becomes ready).
 * 5. WortiseSdk never initializes.
 */
@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Switches the app to its built-in no-ads provider, removing banner, " +
            "interstitial and video pre-roll ads (Yandex, Wortise and mediated networks)."
) {
    compatibleWith(COMPATIBILITY_IPTVREMOTE)

    execute {
        // 1. Ad-supported provider (instream lead-time getter) and its no-ads sibling.
        val provider = AdProviderInstreamLeadFingerprint.method.definingClass
        val base = classDefBy(provider).superclass
            ?: throw PatchException("Ad provider $provider has no superclass")

        val siblings = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.superclass == base && classDef.type != provider) siblings += classDef.type
        }
        val noAds = siblings.singleOrNull()
            ?: throw PatchException("Expected one no-ads provider extending $base, found $siblings")
        // R8 strips the no-ads provider's trivial constructor and the app itself constructs it
        // with `new-instance i5` + `invoke-direct a4.<init>()`; do the same when it is absent.
        fun hasNoArgInit(type: String) =
            classDefBy(type).methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() }
        val initOwner = when {
            hasNoArgInit(noAds) -> noAds
            hasNoArgInit(base) -> base
            else -> throw PatchException("No no-arg constructor for $noAds or $base")
        }

        val baseMethods = classDefBy(base).methods
            .filter { AccessFlags.STATIC.isSet(it.accessFlags).not() && it.name != "<init>" }
            .map { Triple(it.name, it.parameterTypes.map(CharSequence::toString), it.returnType) }
            .toSet()

        val providerClass = mutableClassDefBy(provider)
        val overrides = providerClass.methods.filter { method ->
            method.implementation != null &&
                    (method.returnType.startsWith("L") || method.returnType == "Z") &&
                    Triple(method.name, method.parameterTypes.map(CharSequence::toString), method.returnType) in baseMethods
        }
        if (overrides.isEmpty()) throw PatchException("No provider overrides found on $provider")

        // 2. The placement view factory is the only override taking (Context, placement).
        val placementFactory = overrides.filter {
            it.parameterTypes.size == 2 && it.parameterTypes[0].toString() == CONTEXT
        }.singleOrNull() ?: throw PatchException("Expected one (Context, placement) factory on $provider")

        (overrides - placementFactory).forEach { method ->
            val params = method.parameterTypes.map(CharSequence::toString)
            if (params.any { it == "J" || it == "D" }) {
                throw PatchException("Unexpected wide parameter in ${method.name}")
            }
            // `this` is never needed again, so p0 doubles as the scratch register (as the app's
            // own `new i5()` site does). Some overrides, e.g. the instream page id getter, have
            // no local registers at all.
            // The /range forms keep this valid when p0 lands above v15 in larger methods.
            val args = "p0 .. p${params.size}"
            val signature = "${method.name}(${params.joinToString("")})${method.returnType}"
            val (move, ret) = if (method.returnType == "Z") "move-result" to "return"
            else "move-result-object" to "return-object"

            method.addInstructions(
                0,
                """
                    new-instance p0, $noAds
                    invoke-direct/range {p0 .. p0}, $initOwner-><init>()V
                    invoke-virtual/range {$args}, $noAds->$signature
                    $move p0
                    $ret p0
                """.trimIndent()
            )
        }

        // 3. Banner gate: the provider's only static (Context)Z, `!isTv(context)`. Every banner
        // fragment (list screens, schedule, recordings, player channel list) is added behind it.
        val bannerGate = providerClass.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT) &&
                    it.implementation != null
        }.singleOrNull() ?: throw PatchException("Expected one static banner gate (Context)Z on $provider")
        bannerGate.returnEarly(false)

        // 4. Consent gate: the static (Context, Runnable)V that runs an ad action now or queues it
        // until consent. Dropping the action keeps the preloader (the only other caller of the
        // placement factory) and all interstitial/instream loads from ever starting.
        val consentGate = mutableClassDefBy(AdConsentGateFingerprint.classDef.type).methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, "Ljava/lang/Runnable;") &&
                    it.implementation != null
        }.singleOrNull() ?: throw PatchException("Expected one deferred ad action method on the consent gate")
        consentGate.returnEarly()

        // 5. Belt and braces: Wortise is only reached through the provider, but keep its SDK
        // from initializing at all. Match every concrete overload rather than pinning the
        // R8-renamed listener type.
        val wortiseInit = mutableClassDefBy(WORTISE_SDK).methods.filter {
            it.name == "initialize" && it.returnType == "V" && it.implementation != null
        }
        if (wortiseInit.isEmpty()) throw PatchException("WortiseSdk.initialize not found")
        wortiseInit.forEach { it.returnEarly() }
    }
}
