plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.homedatacenter.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.homedatacenter.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 30
        versionName = "1.5.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Project-fixed keystore so every debug build (regardless of
        // which developer/machine builds it) carries the SAME signature.
        // Without this, a fresh debug build from a different machine
        // cannot overwrite a previously-installed version — Android's
        // package installer reports "安装包无效" / "package invalid"
        // because the SHA-1 fingerprints don't match.
        //
        // The keystore is checked into the repo under app/keystore/
        // intentionally: debug builds are not for distribution and the
        // private key is only used to sign debug APKs, so leaking it
        // does not compromise production signing.
        create("projectDebug") {
            storeFile = file("keystore/home-debug.jks")
            storePassword = "home123"
            keyAlias = "home-debug"
            keyPassword = "home123"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("projectDebug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    // v1.5.4: packaging block — keep useLegacyPackaging=false so AGP
    // stores .so files uncompressed and page-aligned in the APK. This
    // is required for 16 KB page size compatibility on Android 15+
    // devices (along with .so files compiled with -Wl,-z,max-page-size=16384).
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.retrofit.core)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.android)

    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")

    // WebRTC — used for sub-second live streams via go2rtc's /api/v1/webrtc
    // endpoint. Stream's Android WebRTC build is the maintained successor
    // to Google's deprecated org.webrtc:google-webrtc; ships arm64-v8a +
    // armeabi-v7a + x86_64 + x86 ABIs. Used as the primary live transport
    // in v1.5.3 (MP4 + HLS kept as fallback when WebRTC fails or when
    // the backend doesn't expose go2rtc's WebRTC route).
    // v1.5.4: upgraded 1.1.0 → 1.3.10 to fix Android 15+ "16 KB page size
    // not compatible" warning. 1.1.0's libjingle_peerconnection_so.so
    // was compiled with 4 KB LOAD segment alignment; 1.3.x ships .so
    // files aligned to 16 KB. API surface stays compatible
    // (PeerConnectionFactory + PeerConnection.Observer + SurfaceViewRenderer).
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
