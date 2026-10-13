plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val mediaSource = rootProject.file("../modules/memeget-bg/android/src/main")
val reusableMediaFiles = listOf(
    "AudioExtractor.kt", "VideoFrameExtractor.kt", "VideoPlayerFrameExtractor.kt",
    "MemeMediaProbe.kt", "MemeMediaEditor.kt",
    "MemeVideoExporter.kt", "VideoExportPlan.kt",
    "MemeDynamicOverlay.kt", "MemeDrawPrimitives.kt", "MemeDrawPreviewView.kt",
    "MemeTextLayout.kt", "MemeTextDetector.kt", "MemeStillSubjectSegmenter.kt",
    "KeepAliveLease.kt", "KeepAliveService.kt", "MemeClipFileProvider.kt",
)
val syncReusableMedia by tasks.registering(Sync::class) {
    from(File(mediaSource, "java")) {
        reusableMediaFiles.forEach { include("**/$it") }
    }
    into(layout.buildDirectory.dir("generated/reusableMedia"))
}
val syncReusableFonts by tasks.registering(Sync::class) {
    from(File(mediaSource, "assets")) { include("fonts/**") }
    into(layout.buildDirectory.dir("generated/reusableAssets"))
}

android {
    namespace = "com.memeget.nativeapp"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    defaultConfig {
        applicationId = "com.memeget.nativeapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-native"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O2")
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main") {
        java.srcDir(layout.buildDirectory.dir("generated/reusableMedia"))
        assets.srcDir(layout.buildDirectory.dir("generated/reusableAssets"))
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        jniLibs.useLegacyPackaging = false
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
tasks.named("preBuild") { dependsOn(syncReusableMedia, syncReusableFonts) }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-process:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    implementation("androidx.media3:media3-inspector:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.facebook.fbjni:fbjni:0.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.work:work-testing:2.10.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
