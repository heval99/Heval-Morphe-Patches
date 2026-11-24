// Brave "Userscripts": a small userscript manager (store, metadata parser, URL matcher, page
// injection through Brave's Tab.loadUrl and the manager/editor/install screens). Ported from
// Alastor-Kaneki/Morphe-Patches (GPL-3.0-or-later; parts adapted from Violentmonkey, MIT).
// Builds extensions/bravemonkey.mpe. Android SDK only; the Brave classes are reached by reflection.
extension {
    name = "extensions/bravemonkey.mpe"
}

android {
    namespace = "app.template.extension"
}

dependencies {
    // JVM unit tests of the pure helpers: .\gradlew :extensions:bravemonkey:testDebugUnitTest
    testImplementation(libs.junit)
    // Userscript parses and writes JSON; the android.jar stubs would throw.
    testImplementation(libs.json)
}

// Pure-Java extension. AGP's built-in Kotlin adds kotlin-stdlib by default, which would grow the
// .mpe by ~2 MB and merge an unobfuscated Kotlin stdlib into the target app's dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
