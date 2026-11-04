plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yipai.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yipai.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 自用：直接用 debug 密钥签名，生成的 APK 可直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    androidResources {
        noCompress += listOf("onnx")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 自用项目，不让 lint 阻断打包
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    testImplementation("junit:junit:4.13.2")
}
