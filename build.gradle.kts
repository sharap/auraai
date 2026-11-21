// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // For :shared, which is an Android library as well as a JVM one.
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // The desktop port, which came in with its own repository: a Kotlin Multiplatform module
    // targeting the JVM, built by Compose Multiplatform rather than by the Android plugin.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
