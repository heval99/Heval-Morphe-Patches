package app.template.patches.beeper.premium

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_BEEPER
import app.template.patches.shared.addInstructionsChecked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

// Beeper shows a paid plan (FREE / PLUS / PLUS_PLUS / GRANDFATHERED_*) that the backend describes;
// ChatAccountsRepo maps the server's "max-accounts" value onto BeeperPlan, whose concrete subclasses
// answer getMaxAccounts() / getMaxAccountsPerBridge(). The client treats -1 as "unlimited" when it
// decides whether another account may be added.
private const val PLAN_PREFIX = "Lcom/beeper/chat/booper/connect/viewmodel/BeeperPlan$"
private const val EXPECTED_PLANS = 5

// The per-feature allowances (incognito, reminders, scheduled messages, labels, transcription,
// blasts) are "freebies". Their local counters are advanced by one suspend function with this
// shape, which also asks the server to consume the freebie. The class is R8-renamed, so the method
// is found by its parameter list and must match exactly once.
private const val FREEBIE = "Lcom/beeper/freebies/FreebiesRepository\$Freebie;"

private fun Method.isFreebieConsumer() =
    returnType == "Ljava/lang/Object;" &&
        implementation != null &&
        parameterTypes.map(CharSequence::toString) == listOf(
            FREEBIE,
            "Lcom/beeper/freebies/FreebiePromptSurface;",
            "Ljava/lang/String;",
            "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
        )

@Suppress("unused")
val enablePremiumPatch = bytecodePatch(
    name = "Enable Premium",
    description = "Lifts the account and free-tier usage limits.",
) {
    compatibleWith(COMPATIBILITY_BEEPER)

    execute {
        // 1. Every concrete plan answers -1 (unlimited) for the total and per-bridge account caps.
        val planTypes = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(PLAN_PREFIX) &&
                classDef.superclass == "Lcom/beeper/chat/booper/connect/viewmodel/BeeperPlan;" &&
                !AccessFlags.ABSTRACT.isSet(classDef.accessFlags)
            ) {
                planTypes += classDef.type
            }
        }
        if (planTypes.size != EXPECTED_PLANS) {
            throw PatchException("Enable Premium: found ${planTypes.size} BeeperPlan subclasses, expected $EXPECTED_PLANS: $planTypes")
        }
        for (type in planTypes) {
            for (name in listOf("getMaxAccounts", "getMaxAccountsPerBridge")) {
                val method = mutableClassDefBy(type).methods.singleOrNull {
                    it.name == name && it.returnType == "Ljava/lang/Integer;" && it.implementation != null
                } ?: throw PatchException("Enable Premium: $type->$name not found")
                method.addInstructionsChecked(
                    0,
                    """
                        const/4 v0, -0x1
                        invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                        move-result-object v0
                        return-object v0
                    """.trimIndent(),
                )
            }
        }

        // 2. Freebie consumption: return the Kotlin Unit instance the method already uses, so the
        //    app's local counter stays at zero and no consume request is sent.
        val consumerTypes = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.methods.any { it.isFreebieConsumer() }) consumerTypes += classDef.type
        }
        if (consumerTypes.size != 1) {
            throw PatchException("Enable Premium: found ${consumerTypes.size} freebie consumers, expected 1: $consumerTypes")
        }
        val consumer = mutableClassDefBy(consumerTypes.single()).methods.single { it.isFreebieConsumer() }
        val unitCandidates = consumer.implementation!!.instructions
            .filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? FieldReference }
            .filter { field -> field.name == "a" && field.type == field.definingClass }
            .distinct()
        if (unitCandidates.size != 1) {
            throw PatchException("Enable Premium: ${consumer.definingClass}->${consumer.name} has ${unitCandidates.size} candidate Unit instances: $unitCandidates")
        }
        val unit = unitCandidates.single()
        consumer.addInstructionsChecked(
            0,
            """
                sget-object v0, ${unit.definingClass}->${unit.name}:${unit.type}
                return-object v0
            """.trimIndent(),
        )
    }
}
