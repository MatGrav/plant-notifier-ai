// File: build.gradle.kts (Project: PlantNotifier)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false // <--- Cambiato da kotlin.compose a compose.compiler
    alias(libs.plugins.ksp) apply false
}