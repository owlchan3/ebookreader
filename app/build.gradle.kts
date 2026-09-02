import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    kotlin("plugin.parcelize")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

android {
    namespace = "com.ebookreader"
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.ebookreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 68
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Readium Toolkit (via includeBuild composite build)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.navigator.web.reflowable)
    implementation(libs.readium.navigator.web.fixedlayout)
    implementation(libs.readium.adapter.pdfium.document)
    implementation(libs.readium.adapter.pdfium.navigator)
    implementation(libs.readium.opds)
    implementation(libs.readium.lcp)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // DateTime
    implementation(libs.kotlinx.datetime)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coil
    implementation(libs.coil.compose)

    // Jsoup
    implementation(libs.jsoup)

    // Timber
    implementation(libs.timber)

    // PDF Viewer (for PDF rendering via AndroidView)
    implementation("com.github.marain87:AndroidPdfViewer:3.2.8")

    // OkHttp (for AI plugin API calls)
    implementation(libs.okhttp)

}
