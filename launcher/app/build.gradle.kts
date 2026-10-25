plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "familytv.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "familytv.launcher"
        minSdk = 30 // Hisense TV runs Android TV OS 11
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key for now; a real release key comes before installing on the TV.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Local API for the e-paper controller / voice (foundation)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // HTTP client for TMDB + Claude API (recommendations)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // TV control: presses keys through the TV's own adbd on 127.0.0.1:5555 (remote/LocalAdbKeys)
    implementation("dev.mobile:dadb:2.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Fake TMDB / Claude servers for the recommendations tests (no real network calls)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
