import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// v0.4.3: Load persistent signing config from keystore.properties.
// This file is git-ignored and points to a keystore stored OUTSIDE the
// project tree (at /home/z/my-project/keystores/debug.keystore) so it
// survives sandbox rebuilds. If the file doesn't exist (e.g. fresh
// clone), Gradle falls back to the default debug signing config.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.kaiser.aiagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kaiser.aiagent"
        minSdk = 26  // Kept at 26 for backward compat; litertlm requires 32+ but we guard at runtime
        targetSdk = 34
        versionCode = 23
        versionName = "0.5.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // v0.4.3: persistent debug signing config so APK updates don't
        // conflict with "App not installed" errors after sandbox rebuilds.
        create("persistentDebug") {
            val keystorePath = keystoreProperties.getProperty("debugKeystorePath")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = keystoreProperties.getProperty("debugKeystorePassword")
                keyAlias = keystoreProperties.getProperty("debugKeyAlias")
                keyPassword = keystoreProperties.getProperty("debugKeyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Use the persistent keystore if available; otherwise fall
            // back to AGP's default debug signing.
            val keystorePath = keystoreProperties.getProperty("debugKeystorePath")
            if (keystorePath != null && file(keystorePath).exists()) {
                signingConfig = signingConfigs.getByName("persistentDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // v0.5: litertlm 0.13.1 was compiled with Kotlin 2.3; our compiler
        // is 2.1. Skip the metadata version check so we can consume it.
        freeCompilerArgs = listOf("-Xskip-metadata-version-check")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.timber)

    // v0.5: On-device LLM inference via Google's LiteRT-LM (same SDK
    // used by Google's own Edge Gallery app). Lets the agent run models
    // like Gemma-3n-E2B entirely on-device — no API key, no rate limits,
    // no network needed. The native lib (liblitertlm_jni.so) is ~20 MB
    // and only loaded on devices running API 32+.
    implementation(libs.litertlm)

    // v0.4: unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")

    debugImplementation(libs.androidx.ui.tooling)
}
