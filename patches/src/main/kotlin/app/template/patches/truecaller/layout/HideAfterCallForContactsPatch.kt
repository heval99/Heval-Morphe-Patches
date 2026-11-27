package app.template.patches.truecaller.layout

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.template.patches.shared.addInstructionsAtControlFlowLabelChecked
import app.template.patches.shared.addInstructionsChecked
import app.template.patches.shared.addInstructionsWithLabelsChecked
import app.template.patches.truecaller.shared.toStringFields
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import app.template.patches.shared.hevalcontrols.HevalFeature
import app.template.patches.shared.hevalcontrols.addHevalGate
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import app.template.patches.shared.hevalcontrols.featurePatch
import app.template.patches.truecaller.controls.TruecallerControlsCategory
import app.template.patches.truecaller.controls.truecallerControls
import app.template.patches.truecaller.shared.overrideReadFeatureToggles

private const val FEATURE = "truecaller.hide_after_call_contacts"

// A Heval Controls feature: the overrides apply only while it is on.
@Suppress("unused")
val hideAfterCallForContactsPatch = truecallerControls.featurePatch(
    HevalFeature(
        id = FEATURE,
        name = "Hide after-call screen for contacts",
        description = "Hides the after-call screen for saved contacts.",
        category = TruecallerControlsCategory.CONTACTS,
    ),
    default = false,
) {
    // Resolve every hook site before the first edit: a failure leaves the feature neither half-applied nor listed.
    val numberFields = toStringFields(HistoryEventToStringFingerprint.method, HISTORY_EVENT)
    fun historyField(label: String, type: String) = numberFields.filter { (name, field) -> name == label && field.type == type }
        .map { it.second }.singleOrNull()
        ?: throw PatchException("HistoryEvent.$label field not found exactly once")
    fun numberField(label: String) = historyField(label, "Ljava/lang/String;")
    val typeField = historyField("type", "I")
    val contactField = historyField("contact", CONTACT)
    val normalizedField = numberField("normalizedNumber")
    val rawField = numberField("rawNumber")
    val rules = AcsRulesFingerprint.method
    val behavior = FacsBehaviorFingerprint.method
    // kl.b tests the contact twice (the phonebook rule for incoming and outgoing calls); bf.c's first
    // test is the isPhonebook flag that wins over its second (non-phonebook identified) one.
    val ruleSites = rules.savedContactSites(2, 2)
    val behaviorSites = behavior.savedContactSites(2, 1)
    val savedTest = rules.savedContactTest()

    // The after-call rules show the screen for a phonebook contact only when the user's
    // "afterCallForPbContacts" setting AND "featureShowACSAllIncoming" (incoming) or
    // "featureShowACSAllOutgoing" (outgoing) are on. Both toggles now read as off, so
    // contacts never get it. Missed calls (see the entry hook below), unknown numbers and the user's settings
    // ("afterCall", "afterCallForPbContacts", "afterCallForNonPbContacts") are untouched.
    overrideReadFeatureToggles(
        listOf("featureShowACSAllIncoming", "featureShowACSAllOutgoing"),
        false,
        FEATURE,
    )

    // The server-driven FullscreenAcsConfig (Firebase "fullscreenAcsConfig_26137", kept class and getter
    // names) has its own answer for a phonebook contact on a non-missed call; the callers act on it
    // and never reach the rules above unless it is DO_NOTHING (issue #181). Each of the seven
    // phonebook getters now answers DO_NOTHING while the feature is on; the two *PbMissed getters answer
    // it only while the opt-in missed-call switch is on too (the default-dialer path shows the full-screen
    // screen for a missed call without reaching the rules); the non-phonebook getters stay stock. A getter
    // has no free register, so its result goes through a static helper on the config class.
    val config = mutableClassDefBy(FULLSCREEN_ACS_CONFIG)
    val helper = config.addFacsBehaviorHelper()
    val missedHelper = config.addMissedFacsBehaviorHelper()
    for ((names, hook) in listOf(PB_GETTERS to helper, PB_MISSED_GETTERS to missedHelper)) {
        val getters = config.methods.filter { it.name in names }
        if (getters.map { it.name }.sorted() != names.sorted()) {
            throw PatchException("FullscreenAcsConfig phonebook getters not found exactly once: ${getters.map { it.name }}")
        }
        for (getter in getters) {
            if (getter.returnType != FACS_BEHAVIOR || getter.parameterTypes.isNotEmpty() || getter.implementation == null) {
                throw PatchException("Unexpected FullscreenAcsConfig getter shape: $getter")
            }
            // Instruction 1 is the getter's return-object; p0 holds the field value there.
            val last = getter.implementation!!.instructions.size - 1
            getter.addInstructionsChecked(
                last,
                """
                    invoke-static/range { p0 .. p0 }, $hook
                    move-result-object p0
                """,
            )
        }
    }

    // Issue #181 (second part): Truecaller calls a number a saved contact only when its own contact
    // store has an exact match (Contact.k0()), so a contact saved without a country code still counted as
    // unknown. While the feature is on, Android's contacts lookup is asked too (the extension; the
    // number fields are read by a static helper on HistoryEvent, so the sites need only two registers).
    val orSaved = addOrSavedHelper(mutableClassDefBy(HISTORY_EVENT), normalizedField, rawField)
    for ((method, sites) in listOf(rules to ruleSites, behavior to behaviorSites)) {
        // Last site first: an insert shifts the indices after it.
        for (site in sites.sortedByDescending { it.join }) {
            method.addInstructionsAtControlFlowLabelChecked(
                site.join,
                """
                    invoke-static { v${site.result}, v${site.event} }, $orSaved
                    move-result v${site.result}
                """.trimIndent(),
            )
        }
    }

    // Issue #181 (third part): a missed call returns true in the rules before any contact check, and the
    // reporter's app is not the default dialer, so the call-ended handlers reach the rules directly.
    // Entry hook: while the feature is on and the number is a saved contact (Truecaller's store or
    // Android's lookup), the rules answer false at once. Missed calls count only with their own switch.
    val suppress = addSuppressHelper(mutableClassDefBy(HISTORY_EVENT), typeField, contactField, normalizedField, rawField, savedTest)
    rules.addInstructionsWithLabelsChecked(
        0,
        """
            invoke-static/range { p1 .. p1 }, $suppress
            move-result v0
            if-eqz v0, :morphe_continue
            const/4 v0, 0x0
            return v0
            :morphe_continue
            nop
        """.trimIndent(),
    )

    truecallerControls.register(
        HevalFeature(
            id = MISSED_FEATURE,
            name = "Also hide after-call for missed calls from contacts",
            description = "Also hides the after-call screen for missed calls from saved contacts.",
            category = TruecallerControlsCategory.CONTACTS,
            default = false,
            dependencies = listOf(FEATURE),
        ),
    )
}

private const val MISSED_FEATURE = "truecaller.hide_after_call_missed_contacts"
private const val SUPPRESS_NAME = "morpheSuppressAcs"

/** The `Contact.<no-arg Z>` the rules call on a HistoryEvent's contact (Contact.k0() in 26.45.5). */
private fun MutableMethod.savedContactTest(): MethodReference {
    val calls = implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.definingClass == CONTACT && it.returnType == "Z" && it.parameterTypes.isEmpty() }
        .map { it.name }.distinct()
    val name = calls.singleOrNull() ?: throw PatchException("Contact boolean test in the rules not unique: $calls")
    return ImmutableMethodReference(CONTACT, name, emptyList(), "Z")
}

/**
 * Adds `static (HistoryEvent)Z` to HistoryEvent: the extension's shouldSuppress answer for the event's
 * call type, phonebook flag (Contact test, false for a null contact) and numbers.
 */
private fun addSuppressHelper(
    cls: MutableClass, type: FieldReference, contact: FieldReference,
    normalized: FieldReference, raw: FieldReference, savedTest: MethodReference,
): MethodReference {
    val reference = ImmutableMethodReference(cls.type, SUPPRESS_NAME, listOf(HISTORY_EVENT), "Z")
    val helper = ImmutableMethod(
        cls.type, SUPPRESS_NAME, listOf(ImmutableMethodParameter(HISTORY_EVENT, null, null)), "Z",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
        null, null, MutableMethodImplementation(5),
    ).toMutable()
    helper.addInstructionsWithLabelsChecked(
        0,
        """
            iget v0, p0, ${type.definingClass}->${type.name}:${type.type}
            iget-object v1, p0, ${contact.definingClass}->${contact.name}:${contact.type}
            if-eqz v1, :no_contact
            invoke-virtual { v1 }, ${savedTest.definingClass}->${savedTest.name}()Z
            move-result v1
            goto :decide
            :no_contact
            const/4 v1, 0x0
            :decide
            iget-object v2, p0, ${normalized.definingClass}->${normalized.name}:${normalized.type}
            iget-object v3, p0, ${raw.definingClass}->${raw.name}:${raw.type}
            invoke-static { v0, v1, v2, v3 }, $SAVED_CONTACTS_EXTENSION->shouldSuppress(IZLjava/lang/String;Ljava/lang/String;)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
    cls.methods.add(helper)
    return reference
}

/** A `Contact.k0()` test: [join] is where the test's result (in [result]) meets the null-contact path. */
private class SavedContactSite(val join: Int, val result: Int, val event: Int)

/**
 * The tests of "is this a saved contact" in this method: `invoke-virtual Contact.<no-arg Z>`,
 * `move-result vR`, `goto :join`, preceded by the `iget-object HistoryEvent.contact` that gives the
 * HistoryEvent register. A null contact skips the invoke and reaches the join with false, so the hook
 * goes at the join and not at the invoke. Exactly [expected] tests must exist; the first [hooked] (in code
 * order) must have the move-result and goto shape and are returned, the others are left alone.
 */
private fun MutableMethod.savedContactSites(expected: Int, hooked: Int): List<SavedContactSite> {
    val name = "$definingClass->$name"
    val code = implementation ?: throw PatchException("$name has no code")
    val instructions = code.instructions
    val calls = instructions.withIndex().filter { (_, instruction) ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode == Opcode.INVOKE_VIRTUAL && call != null && call.definingClass == CONTACT &&
            call.returnType == "Z" && call.parameterTypes.isEmpty()
    }
    if (calls.size != expected) throw PatchException("$name: found ${calls.size} Contact boolean tests, expected $expected")
    return calls.take(hooked).map { (index, _) ->
        val result = instructions.getOrNull(index + 1)
        val jump = instructions.getOrNull(index + 2)
        if (result?.opcode != Opcode.MOVE_RESULT || jump !is BuilderOffsetInstruction ||
            (jump.opcode != Opcode.GOTO && jump.opcode != Opcode.GOTO_16 && jump.opcode != Opcode.GOTO_32)
        ) throw PatchException("$name: the Contact test at $index is not followed by move-result and goto")
        val contact = instructions.subList(maxOf(0, index - 6), index).lastOrNull {
            val field = (it as? ReferenceInstruction)?.reference as? FieldReference
            it.opcode == Opcode.IGET_OBJECT && field?.definingClass == HISTORY_EVENT && field.type == CONTACT
        } as? TwoRegisterInstruction ?: throw PatchException("$name: no HistoryEvent.contact read before the Contact test at $index")
        SavedContactSite(jump.target.location.index, (result as OneRegisterInstruction).registerA, contact.registerB)
    }
}

private const val OR_SAVED_NAME = "morpheOrSaved"
private const val SAVED_CONTACTS_EXTENSION = "Lapp/template/extension/truecaller/AfterCallSavedContacts;"

/**
 * Adds `static (Z, HistoryEvent)Z` to HistoryEvent: the argument when it is true, else the extension's
 * answer for the event's normalized and raw number (false while the feature is off).
 */
private fun addOrSavedHelper(cls: MutableClass, normalized: FieldReference, raw: FieldReference): MethodReference {
    val reference = ImmutableMethodReference(cls.type, OR_SAVED_NAME, listOf("Z", HISTORY_EVENT), "Z")
    val helper = ImmutableMethod(
        cls.type, OR_SAVED_NAME,
        listOf(ImmutableMethodParameter("Z", null, null), ImmutableMethodParameter(HISTORY_EVENT, null, null)), "Z",
        // Public: it is called from other classes (the rules and the behaviour method).
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
        null, null, MutableMethodImplementation(5),
    ).toMutable()
    helper.addInstructionsWithLabelsChecked(
        0,
        """
            if-nez p0, :done
            iget-object v0, p1, ${normalized.definingClass}->${normalized.name}:${normalized.type}
            iget-object v1, p1, ${raw.definingClass}->${raw.name}:${raw.type}
            invoke-static { v0, v1 }, $SAVED_CONTACTS_EXTENSION->isSaved(Ljava/lang/String;Ljava/lang/String;)Z
            move-result p0
            :done
            return p0
        """.trimIndent(),
    )
    cls.methods.add(helper)
    return reference
}

private const val FULLSCREEN_ACS_CONFIG= "Lcom/truecaller/incallui/utils/FullscreenAcsConfig;"
private const val FACS_BEHAVIOR = "Lcom/truecaller/acs/util/FacsBehavior;"

// Phonebook-contact behaviours of calls that were answered, dismissed or placed. The *PbMissed ones (missed
// calls show the screen by design, issue #156) are gated by the opt-in missed-call switch instead.
private val PB_GETTERS = listOf(
    "getUnlockedPbIncomingAccept", "getUnlockedPbIncomingDismiss", "getUnlockedPbIncomingDismissNotification",
    "getUnlockedPbOutgoing", "getLockedPbIncomingAccept", "getLockedPbIncomingDismiss", "getLockedPbOutgoing",
)

private val PB_MISSED_GETTERS = listOf("getUnlockedPbMissed", "getLockedPbMissed")

private const val HELPER_NAME = "morpheHideAfterCallForContacts"
private const val MISSED_HELPER_NAME = "morpheHideAfterCallMissedForContacts"

/**
 * Adds `static (FacsBehavior)FacsBehavior`: DO_NOTHING while the feature is on, else the argument.
 */
private fun MutableClass.addFacsBehaviorHelper(): MethodReference {
    val reference = ImmutableMethodReference(type, HELPER_NAME, listOf(FACS_BEHAVIOR), FACS_BEHAVIOR)
    val helper = ImmutableMethod(
        type, HELPER_NAME, listOf(ImmutableMethodParameter(FACS_BEHAVIOR, null, null)), FACS_BEHAVIOR,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
        null, null, MutableMethodImplementation(2),
    ).toMutable()
    helper.addInstructionsChecked(0, "return-object p0")
    helper.addHevalGate(FEATURE) { "sget-object p0, $FACS_BEHAVIOR->DO_NOTHING:$FACS_BEHAVIOR" }
    methods.add(helper)
    return reference
}

/**
 * Adds `static (FacsBehavior)FacsBehavior`: DO_NOTHING while both the contacts switch and the missed-call
 * switch are on (the extension asks), else the argument.
 */
private fun MutableClass.addMissedFacsBehaviorHelper(): MethodReference {
    val reference = ImmutableMethodReference(type, MISSED_HELPER_NAME, listOf(FACS_BEHAVIOR), FACS_BEHAVIOR)
    val helper = ImmutableMethod(
        type, MISSED_HELPER_NAME, listOf(ImmutableMethodParameter(FACS_BEHAVIOR, null, null)), FACS_BEHAVIOR,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
        null, null, MutableMethodImplementation(2),
    ).toMutable()
    helper.addInstructionsWithLabelsChecked(
        0,
        """
            invoke-static { }, $SAVED_CONTACTS_EXTENSION->hideMissedBehavior()Z
            move-result v0
            if-eqz v0, :stock
            sget-object p0, $FACS_BEHAVIOR->DO_NOTHING:$FACS_BEHAVIOR
            :stock
            return-object p0
        """.trimIndent(),
    )
    methods.add(helper)
    return reference
}
