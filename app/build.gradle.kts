import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-kapt")
}

android {
    namespace = "com.electrodig.voidmusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.electrodig.voidmusic"
        minSdk = 26          // PRD: Android 8.0 (API 26)+
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-m7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Debug: keep x86_64 for emulator testing.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Release: arm64-v8a only to keep APK under 80 MB (PRD §6.2.3 / M6 R6.4).
            // Add armeabi-v7a if needed for broader compatibility (adds ~24 MB).
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
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
        buildConfig = true
        // Expose the Oboe prefab AAR headers/lib to our CMake build (PRD §6.2.2).
        prefab = true
    }

    // NDK + CMake build for the Oboe drum engine (libdrumengine.so).
    // Controlled via gradle.properties: enableNativeBuild=true
    // Requires NDK 27.2.12479018 installed via SDK Manager.
    ndkVersion = "27.2.12479018"
    val enableNativeBuild = project.findProperty("enableNativeBuild")?.toString()?.toBooleanStrictOrNull() ?: false
    if (enableNativeBuild) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        defaultConfig.externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    // Keep MediaPipe .task model in the APK uncompressed so it can be mmap-ed.
    androidResources {
        noCompress += listOf("task")
    }

    packaging {
        jniLibs {
            // Oboe and OpenCV both depend on the NDK shared C++ runtime. Package
            // one copy per ABI so AGP does not make an arbitrary future choice.
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM-managed versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // CameraX (PRD F1: 摄像头取景器)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // MediaPipe Tasks Vision (PRD F5.1: HandLandmarker 手部追踪)
    implementation(libs.mediapipe.tasks.vision)

    // OpenCV (PRD F4.3 / §9.1: HSV 分割 + 连通域 + 透视变换)
    implementation(libs.opencv.android)

    // Oboe low-latency audio (PRD F6.2 / §9.5: prefab AAR consumed by CMake).
    implementation(libs.oboe)

    // Permissions (PRD F1.2: 运行时权限申请)
    implementation(libs.accompanist.permissions)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Persistence (PRD F8: 设置 / 序列持久化)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
