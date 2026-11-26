package app.template.patches.outfit7.ads

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.template.patches.shared.Constants.COMPATIBILITY_MY_TALKING_TOM
import app.template.patches.shared.Constants.COMPATIBILITY_MY_TALKING_TOM_2
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes the interstitial, banner and splash ads."
) {
    compatibleWith(COMPATIBILITY_MY_TALKING_TOM, COMPATIBILITY_MY_TALKING_TOM_2)

    execute {
        // 1. The one concrete InventoryBinding implementation. Its abstract interface
        // declarations have no body, and unrelated AppLovin/APS classes share the method
        // names, so the interface is the anchor.
        val implementers = mutableListOf<String>()
        classDefForEach { classDef ->
            if (INVENTORY_BINDING in classDef.interfaces) implementers += classDef.type
        }
        val binding = implementers.singleOrNull()
            ?.let { mutableClassDefBy(it) }
            ?: throw PatchException("Disable ads: expected one $INVENTORY_BINDING implementer, found $implementers")

        fun concrete(name: String, returnType: String, vararg parameters: String) =
            binding.methods.filter {
                it.name == name && it.returnType == returnType &&
                    it.parameterTypes.map(CharSequence::toString) == parameters.toList() &&
                    it.implementation != null
            }.let { matches ->
                matches.singleOrNull()
                    ?: throw PatchException("Disable ads: expected one concrete ${binding.type}->$name, found ${matches.size}")
            }

        // 2. The game asks isAdsEnabled() before every ad flow and relays it to Unity as
        // _OnAdsEnabled; false is the state the app already handles for a bought "remove ads".
        concrete("isAdsEnabled", "Z").returnEarly(false)

        // 3. Interstitials never load or show (the game only shows after a load callback), and
        // the banner holder is never asked to show. Rewarded video is left alone: it is an
        // opt-in "watch to get coins" offer. Promo news and the dream bubble are Outfit7's own
        // content and also untouched.
        concrete("loadInterstitialAd", "V").returnEarly()
        concrete("showInterstitialAd", "V").returnEarly()
        concrete("setBannerAdVisible", "V", "Z").returnEarly()

        // 4. Splash ad at launch. Its loader skips loading for a user who removed ads; make
        // that check answer true so no splash is requested and the show step reports "Splash
        // ad unavailable", the stock no-fill path.
        val splash = SplashAdLoadFingerprint.method
        val flag = splash.implementation!!.instructions.withIndex().filter { (_, insn) ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            insn.opcode == Opcode.INVOKE_INTERFACE && ref != null && ref.returnType == "Z" &&
                ref.parameterTypes.isEmpty()
        }.singleOrNull() ?: throw PatchException("Disable ads: splash loader's billing check not found")
        val result = splash.implementation!!.instructions.elementAt(flag.index + 1)
        if (result.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("Disable ads: splash billing check has no move-result")
        }
        val register = (result as OneRegisterInstruction).registerA
        if (register > 15) throw PatchException("Disable ads: splash billing register v$register too wide for const/4")
        splash.replaceInstruction(flag.index + 1, "const/4 v$register, 0x1")
    }
}
