// Kotlin is built into AGP 9 (no org.jetbrains.kotlin.android plugin); the Compose
// compiler plugin follows AGP's bundled Kotlin version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
