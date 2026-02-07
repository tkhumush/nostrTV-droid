plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

android {
    namespace = "com.nostrtv.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nostrtv.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)

    // Compose for TV
    implementation("androidx.tv:tv-foundation:1.0.0-alpha12")
    implementation("androidx.tv:tv-material:1.0.1")

    // Core Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Leanback (TV support)
    implementation("androidx.leanback:leanback:1.0.0")

    // ExoPlayer for video playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for WebSocket connections
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // QR Code generation for zaps
    implementation("com.google.zxing:core:3.5.2")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Security for encrypted preferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Secp256k1 for Nostr cryptography (NIP-04, NIP-46)
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.14.0")

    // Quartz - Amethyst's Nostr library with NIP-44/NIP-46 support
    implementation("com.vitorpamplona.quartz:quartz:1.03.0")

    // LibSodium for ChaCha20-IETF (NIP-44 encryption) - required by Quartz
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
