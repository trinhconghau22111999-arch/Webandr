plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.h.webandr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.h.webandr"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Chi dong goi cho Android x64 (x86_64).
        ndk {
            abiFilters += listOf("x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ky bang khoa debug de APK cai duoc ngay (khong can keystore rieng).
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
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
