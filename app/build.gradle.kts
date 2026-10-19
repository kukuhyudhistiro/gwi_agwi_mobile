plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kukuh.agwi"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kukuh.agwi"
        minSdk = 26; targetSdk = 36
        versionCode = 1; versionName = "1.0-agwi"
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-O3", "-ffast-math")
                arguments("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                cppFlags("-Wl,-z,max-page-size=16384")
            }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    buildFeatures { compose = true; prefab = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraV = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraV")
    implementation("androidx.camera:camera-camera2:$cameraV")
    implementation("androidx.camera:camera-lifecycle:$cameraV")
    implementation("androidx.camera:camera-view:$cameraV")

    // OpenCV: Using official Maven AAR (supports Prefab for native linking)
    implementation("org.opencv:opencv:4.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
