plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23"
}

android {
    namespace = "com.taha.goit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taha.goit"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "BURULAS_API_BASE_URL", "\"https://bursakartapi.abys-web.com\"")
        buildConfigField("String", "BURULAS_ORIGIN_HEADER", "\"https://www.bursakart.com.tr\"")
        buildConfigField("String", "OSRM_API_BASE_URL", "\"https://routing.openstreetmap.de/routed-foot\"")
        buildConfigField("String", "NOMINATIM_API_BASE_URL", "\"https://nominatim.openstreetmap.org\"")
    }

    signingConfigs {
        val ksPath = System.getenv("GOIT_KEYSTORE_PATH")
        val ksFile = if (!ksPath.isNullOrEmpty()) file(ksPath) else null

        if (ksFile != null && ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("GOIT_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("GOIT_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("GOIT_KEY_PASSWORD") ?: "android"
            }
            maybeCreate("debug").apply {
                storeFile = ksFile
                storePassword = System.getenv("GOIT_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("GOIT_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("GOIT_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            val debugSigning = signingConfigs.findByName("debug")
            if (debugSigning != null) {
                signingConfig = debugSigning
            }
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        // 16 KB page-size devices require uncompressed native libraries so the
        // loader can mmap them straight from the APK (16 KB zip-aligned).
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // MapLibre Native for Android (FOSS Maps - No Google Play Services trackers)
    // 11.13.5: 16 KB page-size uyumlu native kütüphaneler (arm64/x86_64 LOAD align 0x4000)
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    // Minimal Networking (OkHttp + Kotlin Serialization)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.10")
}
