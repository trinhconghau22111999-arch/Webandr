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
        // Moi lan build tren GitHub Actions, so phien ban tu tang (GITHUB_RUN_NUMBER) de cai de len duoc.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "1.2.$run"

        // Chi dong goi cho Android x64 (x86_64).
        ndk {
            abiFilters += listOf("x86_64")
        }
    }

    // Khoa ky CO DINH (webandr.jks) -> cac ban build deu cung chu ky, cai de len ban cu khong can go.
    signingConfigs {
        create("fixed") {
            storeFile = file("webandr.jks")
            storePassword = "webandr123"
            keyAlias = "webandr"
            keyPassword = "webandr123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
