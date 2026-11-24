package app.template.patches.listonic.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val ADCOMPANION = "Lcom/listonic/adcompanion/core/AdCompanion;"

// AdCompanion's lock manager (class name rotated by R8, so it is matched by shape): a(lock)V
// releases a lock and b(lock)V engages one. Release is the one that returns when the lock is NOT
// in the set (Set.contains, if-nez), then rebuilds the set, builds a LockReleased event from the
// lock enum's name() and logs it through AdCompanion.logEvent$AdCompanion_core. Engage is the same
// with if-eqz, so it has no if-nez and never matches.
internal object LockReleaseFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(definingClass = "Ljava/util/Set;", name = "contains"),
        opcode(Opcode.IF_NEZ),
        methodCall(definingClass = "Ljava/lang/Enum;", name = "name"),
        methodCall(definingClass = ADCOMPANION, name = "logEvent\$AdCompanion_core"),
    ),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null
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
