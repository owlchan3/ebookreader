plugins {
    // AGP 9.0 has built-in Kotlin support — no kotlin-android plugin needed
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
