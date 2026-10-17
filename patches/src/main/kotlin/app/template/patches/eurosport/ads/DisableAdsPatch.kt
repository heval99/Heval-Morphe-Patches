package app.template.patches.eurosport.ads

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.returnEarly
import app.template.patches.eurosport.shared.AD_SDKS
import app.template.patches.eurosport.shared.constZero
import app.template.patches.eurosport.shared.disableSdkConfigs
import app.template.patches.eurosport.shared.insertBefore
import app.template.patches.eurosport.shared.returnNull
import app.template.patches.eurosport.shared.single
import app.template.patches.eurosport.shared.singleStaticFactory
import app.template.patches.eurosport.shared.singleStore
import app.template.patches.eurosport.shared.strings
import app.template.patches.shared.Constants.COMPATIBILITY_EUROSPORT
import app.template.patches.shared.addInstructionsChecked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private fun Instruction.stringRef() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference

/** Whether this invoke (or filled-new-array) passes [register]. */
private fun Instruction.passes(register: Int): Boolean = when (this) {
    is RegisterRangeInstruction -> register in startRegister until startRegister + registerCount
    is FiveRegisterInstruction ->
        listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount).contains(register)
    else -> false
}

/**
 * The PlayerModel field toString() prints after "showAds=": the first boolean field read after
 * the instruction that consumes that string, and before the next string is loaded (so a helper
 * that took the flag along with the string fails loudly instead of picking the next field).
 */
private fun showAdsField(toString: MutableMethod, playerModel: String): FieldReference {
    val insns = toString.implementation!!.instructions.toList()
    val label = insns.indexOfFirst { it.stringRef()?.contains("showAds=") == true }
    val register = (insns[label] as OneRegisterInstruction).registerA
    val consumer = (label + 1 until insns.size).firstOrNull { insns[it].passes(register) }
        ?: throw PatchException("PlayerModel toString never uses its showAds= string")
    for (insn in insns.drop(consumer + 1)) {
        if (insn.stringRef() != null) break
        val field = insn.fieldRef()
        if (insn.opcode == Opcode.IGET_BOOLEAN && field?.definingClass == playerModel) return field
    }
    throw PatchException("PlayerModel toString reads no boolean field right after showAds=")
}

/** Every concrete StorytellerModule implementation in the app (through sub-interfaces too). */
private fun BytecodePatchContext.storytellerModules(): List<String> {
    val interfaces = mutableMapOf<String, List<String>>()
    val concrete = mutableSetOf<String>()
    classDefForEach { classDef ->
        if (classDef.interfaces.none { it.startsWith("Lcom/storyteller/") }) return@classDefForEach
        interfaces[classDef.type] = classDef.interfaces.toList()
        if (!AccessFlags.INTERFACE.isSet(classDef.accessFlags) && !AccessFlags.ABSTRACT.isSet(classDef.accessFlags)) {
            concrete += classDef.type
        }
    }
    fun isModule(type: String, seen: Set<String> = emptySet()): Boolean =
        type == STORYTELLER_MODULE ||
            (type !in seen && interfaces[type].orEmpty().any { isModule(it, seen + type) })
    return concrete.filter { isModule(it) }
}

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes the banner and in-feed ads, the ads in the Stories and Clips players and the " +
        "pre-roll ads on free videos. Ads inserted into live and premium streams are part of the stream " +
        "and stay.",
) {
    compatibleWith(COMPATIBILITY_EUROSPORT)

    execute {
        // 1. The remote kill switch reads GOOGLE_AD and TEADS as off: the ad view factory returns a
        // 0x0 view instead of a Google Ad Manager or Teads slot.
        disableSdkConfigs(AD_SDKS)

        // 2. The AdPlaceholderModel factory (8.33.0: static pjg.r(dm)) answers null, so the feed,
        // article and match page builders add no ad slot. All five callers null-check it.
        val placeholder = single(AdPlaceholderToStringFingerprint, "AdPlaceholderModel toString").classDef.type
        val factory = singleStaticFactory(placeholder, "AdPlaceholderModel")
        val providerClasses = UnsupportedAdProviderFingerprint.matchAllOrNull().orEmpty().map { it.classDef.type }
        if (factory.classDef.type !in providerClasses) {
            throw PatchException(
                "The AdPlaceholderModel factory ${factory.classDef.type}->${factory.method.name} is not next to " +
                    "the \"Unsupported provider: \" method (found in $providerClasses)",
            )
        }
        factory.method.returnNull()

        // 3. PlayerModel's showAds is stored as false in every constructor, so free (VOD) videos
        // get no ad parameters. The field is found through toString(); 8.33.0 stores p13.
        val player = single(PlayerModelToStringFingerprint, "PlayerModel toString")
        val showAds = showAdsField(player.method, player.classDef.type)
        val playerConstructors = player.classDef.methods.filter { method ->
            method.name == "<init>" && method.implementation?.instructions?.any {
                it.opcode == Opcode.IPUT_BOOLEAN && it.fieldRef()?.let { ref -> ref.name == showAds.name && ref.definingClass == showAds.definingClass } == true
            } == true
        }
        if (playerConstructors.isEmpty()) throw PatchException("No PlayerModel constructor stores ${showAds.name}")
        playerConstructors.forEach { constructor ->
            val (index, register) = constructor.singleStore(Opcode.IPUT_BOOLEAN, showAds.name, "Z")
            constructor.insertBefore(index, constZero(register))
        }

        // 4. Storyteller Settings: both constructors (the public one and the serializer's, which
        // leads with the field mask) store AdSource.NoAds, so Stories and Clips request no ads.
        // AdSource is the parameter enum whose <clinit> names NoAds, IntegratingApp and Storyteller.
        val settings = mutableClassDefByOrNull(STORYTELLER_SETTINGS)
            ?: throw PatchException("$STORYTELLER_SETTINGS not found")
        val settingsConstructors = settings.methods.filter { it.name == "<init>" && it.implementation != null }
        val adSources = settingsConstructors.flatMap { it.parameterTypes.map(CharSequence::toString) }.toSet()
            .filter { type ->
                classDefByOrNull(type)?.let { enum ->
                    enum.superclass == "Ljava/lang/Enum;" &&
                        enum.methods.firstOrNull { it.name == "<clinit>" }?.strings()
                            ?.containsAll(listOf("NoAds", "IntegratingApp", "Storyteller")) == true
                } == true
            }
        val adSource = adSources.singleOrNull()
            ?: throw PatchException("Expected one Storyteller AdSource parameter type, found $adSources")
        val noAds = classDefByOrNull(adSource)!!.methods.first { it.name == "<clinit>" }.implementation!!.instructions
            .toList().let { clinit ->
                val name = clinit.indexOfFirst { it.stringRef() == "NoAds" }
                clinit.drop(name).firstOrNull { insn ->
                    insn.opcode == Opcode.SPUT_OBJECT && insn.fieldRef()?.let { it.definingClass == adSource && it.type == adSource } == true
                }?.fieldRef()
            } ?: throw PatchException("AdSource $adSource has no NoAds constant")
        val adSourceField = settings.fields.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == adSource }
            ?: throw PatchException("$STORYTELLER_SETTINGS has no single $adSource field")
        val adSourceConstructors = settingsConstructors.filter { constructor ->
            constructor.parameterTypes.any { it.toString() == adSource }
        }
        val serializerShape = adSourceConstructors.count { it.parameterTypes.firstOrNull()?.toString() == "I" }
        if (serializerShape != 1 || adSourceConstructors.size != 2) {
            throw PatchException("Expected the public and the serializer Settings constructor, found ${adSourceConstructors.size}")
        }
        adSourceConstructors.forEach { constructor ->
            val (index, register) = constructor.singleStore(Opcode.IPUT_OBJECT, adSourceField.name, adSource)
            constructor.insertBefore(index, "sget-object v$register, $adSource->${noAds.name}:$adSource")
        }

        // 5. Clips: no bottom banner and no pre-roll ad, whatever the configuration says.
        val clipsAds = mutableClassDefByOrNull(STORYTELLER_CLIPS_AD_CONFIGURATION)
            ?: throw PatchException("$STORYTELLER_CLIPS_AD_CONFIGURATION not found")
        for (getter in listOf("getBottomBannerEnabled", "getPreRollEnabled")) {
            clipsAds.methods.singleOrNull {
                it.name == getter && it.returnType == "Z" && it.parameterTypes.isEmpty() && it.implementation != null
            }?.returnEarly(false) ?: throw PatchException("$STORYTELLER_CLIPS_AD_CONFIGURATION.$getter not found")
        }

        // 6. Storyteller gets no modules. The app registers only the Google Ad Manager module
        // (StorytellerGamModule); the patch refuses to run if a non-ads module exists.
        val modules = storytellerModules()
        val nonAdModules = modules.filterNot { it.startsWith(STORYTELLER_ADS_MODULES) }
        if (modules.isEmpty() || nonAdModules.isNotEmpty()) {
            throw PatchException("Storyteller modules other than the ads modules exist: $nonAdModules (all: $modules)")
        }
        val setModules = mutableClassDefByOrNull(STORYTELLER_COMPANION)?.methods?.singleOrNull {
            it.name == "setModules" && it.returnType == "V" && it.implementation != null &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;")
        } ?: throw PatchException("$STORYTELLER_COMPANION.setModules(List)V not found")
        setModules.addInstructionsChecked(
            0,
            """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object p1
            """,
        )

        // 7. Belt and braces for step 1: the app's Teads view (8.33.0: wkg.load) never creates
        // its TeadsAdPlacementMedia. Optional: a build without a Teads view needs nothing here.
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Ltv/teads/")) return@classDefForEach
            fun createsTeadsPlacement(method: Method) =
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.NEW_INSTANCE &&
                        ((it as ReferenceInstruction).reference as? TypeReference)?.type == TEADS_AD_PLACEMENT_MEDIA
                } == true
            if (classDef.methods.none(::createsTeadsPlacement)) return@classDefForEach
            mutableClassDefBy(classDef).methods.filter(::createsTeadsPlacement).forEach { loader ->
                if (loader.returnType != "V") {
                    throw PatchException("Teads loader ${classDef.type}->${loader.name} does not return void")
                }
                loader.returnEarly()
            }
        }
    }
}
