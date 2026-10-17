// Build config for the single application module.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Kotlin 2.0 Compose compiler; no manual extension version
}

android {
    namespace = "com.lumilink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lumilink"
        minSdk = 29          // WifiNetworkSpecifier (the connectivity fix) requires API 29+
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose) // collectAsStateWithLifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose — the BOM aligns all Compose artifact versions; individual libs omit versions.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)  // wifi/focus/record/grid icons for MVP2
    implementation(libs.androidx.navigation.compose)

    implementation(libs.okhttp)                        // cam.cgi HTTP + file downloads + SOAP
    implementation(libs.coil.compose)                  // async thumbnail loading in Compose
    implementation(libs.androidx.datastore.preferences) // persist saved camera SSID/passphrase

    debugImplementation(libs.androidx.ui.tooling)      // Compose preview tooling (debug only)
    testImplementation(libs.junit)                     // JVM unit tests (parsers)
}
