package app.template.patches.tipsbladet.misc.telemetry

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// Tipsbladet is a React Native app using @react-native-firebase/analytics. The JS bundle calls
// firebase.analytics().setAnalyticsCollectionEnabled(...) as part of its consent flow, which would
// override the manifest switches, so the native module's entry point is pinned too. The module class
// and its @ReactMethod names are library API (not R8-obfuscated) and the method is the only one with
// this exact shape.
internal object AnalyticsCollectionSwitchFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef ->
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
            classDef.type == "Lio/invertase/firebase/analytics/ReactNativeFirebaseAnalyticsModule;" &&
            method.name == "setAnalyticsCollectionEnabled" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0].toString() == "Ljava/lang/Boolean;" &&
            method.implementation != null
    },
)
