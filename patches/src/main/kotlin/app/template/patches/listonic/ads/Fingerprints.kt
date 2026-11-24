package app.template.patches.listonic.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

// AdCompanion's lock manager (class name rotated by R8): a(lock)V releases a lock and b(lock)V
// engages one. Each logs an event class whose data-class toString names it ("LockReleased(lockType="
// / "LockEngaged(lockType="), so the release method is the non-static (L)V that new-instances the
// LockReleased class. The engage method builds LockEngaged and never matches.
internal const val LOCK_RELEASED_EVENT_STRING = "LockReleased(lockType="

/** The lock manager's release method: the one non-static (L)V that creates a [releasedEvent]. */
internal fun lockReleaseFingerprint(releasedEvent: String) = Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == releasedEvent
        } == true
    },
)
// AdProviderRegistry.initializeEager(scope): launches one coroutine per ad provider (AppLovin
// MAX, Google Mobile Ads, Amazon APS) that calls AdProviderCore.ensureInitialized. Class and
// method name are kept by R8; the scope parameter type is obfuscated.
internal object InitializeEagerFingerprint : Fingerprint(
    definingClass = "Lcom/listonic/adcompanion/core/provider/AdProviderRegistry;",
    name = "initializeEager",
    returnType = "V",
    custom = { method, _ -> method.implementation != null },
)

// AppConfiguration(theme, ..., premiumActive, isAdsEnabled), a Kotlin data class whose toString
// names its properties. The constructor takes three objects and the two booleans.
internal object AppConfigurationConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf("L", "L", "L", "Z", "Z"),
    custom = { _, classDef ->
        classDef.methods.any { toString ->
            toString.name == "toString" && toString.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "AppConfiguration(theme="
            } == true
        }
    },
)
