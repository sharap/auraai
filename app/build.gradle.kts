plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Where ModelRepository fetches CLAP weights that are not bundled into the APK.
// Override in gradle.properties (or -P on the command line) to point at a different mirror.
val hfModelRepo: String = providers.gradleProperty("auraai.hf.repo")
    .getOrElse("Xenova/larger_clap_music_and_speech")
val hfModelRevision: String = providers.gradleProperty("auraai.hf.revision").getOrElse("main")

// The two .onnx files in assets are ~408 MB. Build with -Pauraai.bundleModels=false to leave them
// out; the app then downloads them from the repo above on first use (Settings -> AI Models).
val bundleModels: Boolean = providers.gradleProperty("auraai.bundleModels")
    .getOrElse("true").toBoolean()

// A release build is unsigned by default and cannot be installed, which makes it awkward to test
// the things that only exist in release: R8, resource shrinking, no debug overhead. Signing it with
// the debug key makes `installRelease` work without a real keystore.
//
// This is a development convenience and must not ship: an APK signed with the debug key cannot be
// uploaded to Play, and every machine's debug key is different. Pass
// -Pauraai.signReleaseWithDebugKey=false (or wire up a real signing config) for a distributable
// build.
val signReleaseWithDebugKey: Boolean = providers.gradleProperty("auraai.signReleaseWithDebugKey")
    .getOrElse("true").toBoolean()

// ONNX Runtime ships a native library per ABI and all four land in the APK — about 58 MB, of which
// a given phone uses one. Narrow it while developing with e.g. -Pauraai.abi=arm64-v8a; leave it
// unset for a build that has to run anywhere.
val devAbis: List<String> = providers.gradleProperty("auraai.abi").orNull
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

android {
    namespace = "music.ai.recommend"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "music.ai.recommend"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "HF_MODEL_REPO", "\"$hfModelRepo\"")
        buildConfigField("String", "HF_MODEL_REVISION", "\"$hfModelRevision\"")

        if (devAbis.isNotEmpty()) {
            ndk { abiFilters += devAbis }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signReleaseWithDebugKey) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // ONNX weights are already densely packed, so compressing them buys little. Storing them
        // uncompressed turns first-run extraction into a plain copy instead of an inflate.
        noCompress += "onnx"
        if (!bundleModels) {
            ignoreAssetsPatterns += "*.onnx"
        }
    }
}

if (signReleaseWithDebugKey) {
    // Said out loud at package time rather than at configuration time, so it is not swallowed by a
    // configuration cache hit.
    tasks.matching { it.name == "packageRelease" }.configureEach {
        doFirst {
            logger.lifecycle(
                "AuraAI: this release APK is signed with the DEBUG key — development only. " +
                    "Build with -Pauraai.signReleaseWithDebugKey=false for a distributable build."
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.google.code.gson)
    implementation(libs.coil.compose)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    annotationProcessor(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

