import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

private const val INVENTORY_BINDING = "Lcom/outfit7/engine/inventory/InventoryBinding;"

/**
 * Shared assertions for the two Outfit7 games' "Disable ads" patch. Finds the InventoryBinding
 * implementer and the splash loader the way the patch does (by interface and by log literal),
 * so the test survives R8 renaming.
 */
fun assertOutfit7AdsDisabled(classes: List<ClassDef>) {
    val implementers = classes.filter { INVENTORY_BINDING in it.interfaces }
    assertEquals(1, implementers.size, "expected one InventoryBinding implementer")
    val binding = implementers.single()

    // The stock comparison must never be skipped silently.
    for ((name, parameters) in listOf(
        "isAdsEnabled" to emptyList(),
        "loadInterstitialAd" to emptyList(),
        "showInterstitialAd" to emptyList(),
        "setBannerAdVisible" to listOf("Z"),
    )) {
        assertNotNull(StockMethods.find(binding.method(name, parameters)), "no stock method for ${binding.type}.$name")
    }

    assertForcedBoolean(binding.method("isAdsEnabled", emptyList()), expected = false, label = "${binding.type}.isAdsEnabled()")
    assertReturnsEarlyVoid(binding.method("loadInterstitialAd", emptyList()), "${binding.type}.loadInterstitialAd()")
    assertReturnsEarlyVoid(binding.method("showInterstitialAd", emptyList()), "${binding.type}.showInterstitialAd()")
    assertReturnsEarlyVoid(binding.method("setBannerAdVisible", listOf("Z")), "${binding.type}.setBannerAdVisible(Z)")

    // Left alone on purpose: rewarded video, banner state getters, promo news and dream bubble.
    for ((name, parameters) in listOf(
        "loadRewardedAd" to emptyList(),
        "showRewardedAd" to emptyList(),
        "isBannerAdVisible" to emptyList(),
        "setBannerAdPosition" to listOf("I"),
        "getPromoNews" to emptyList(),
        "getDreamBubble" to emptyList(),
    )) {
        val method = binding.method(name, parameters)
        assertTrue(method.instructions().size > 1, "${binding.type}.$name was neutralised but must stay untouched")
        assertNotEquals(Opcode.RETURN_VOID, method.instructions().first().opcode, "${binding.type}.$name returns at once")
    }

    // Splash loader: the one coroutine holding the "Load splash ad" literal.
    val loaders = classes.flatMap { it.methods }.filter { method ->
        method.instructions().any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "Load splash ad"
        }
    }
    assertEquals(1, loaders.size, "expected one splash loader")
    val loader = loaders.single()
    assertNotNull(StockMethods.find(loader), "no stock method for splash loader ${loader.definingClass}")
    assertChangedFromStock(loader, "splash loader ${loader.definingClass}")
    val instructions = loader.instructions()
    val checks = instructions.withIndex().filter { (_, insn) ->
        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
        insn.opcode == Opcode.INVOKE_INTERFACE && ref?.returnType == "Z" && ref.parameterTypes.isEmpty()
    }
    assertEquals(1, checks.size, "expected one billing check in the splash loader")
    val forced = instructions[checks.single().index + 1]
    assertTrue(
        forced.opcode == Opcode.CONST_4 && (forced as NarrowLiteralInstruction).narrowLiteral == 1,
        "splash billing check result is not forced to true; next instruction is ${forced.opcode}",
    )
    val branch = instructions[checks.single().index + 2]
    assertTrue(
        branch.opcode == Opcode.IF_EQZ &&
            (branch as OneRegisterInstruction).registerA == (forced as OneRegisterInstruction).registerA,
        "splash const/4 does not write the register the following if-eqz reads; next is ${branch.opcode}",
    )
}
