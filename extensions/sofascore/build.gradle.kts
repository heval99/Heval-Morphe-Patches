// Sofascore helpers for Heval Controls: the "Heval Controls" row on the Settings screen and the
// runtime hooks of the Sofascore features (modal queue, match tabs, sports list). Builds
// extensions/sofascore.mpe; the Heval Controls core is compileOnly (the host patch merges
// extensions/hevalcontrols.mpe), so this .mpe never carries a second copy of it.
// See docs/heval-controls.md.
extension {
    name = "extensions/sofascore.mpe"
}

android {
    namespace = "app.template.extension"
}

dependencies {
    compileOnly(project(":extensions:hevalcontrols:core"))
}

// Pure-Java extension. AGP's built-in Kotlin adds kotlin-stdlib by default, which would grow the
// .mpe by ~2 MB and merge an unobfuscated Kotlin stdlib into the target app's dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
