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

// One APK per ABI instead of one that carries all four, plus a universal APK for the cases where
// the target's architecture is unknown.
//
// Splits are configured for the whole project rather than per build type, so they are keyed off the
// requested tasks: a debug build keeps producing the single APK that `adb install` and the
// instrumentation tests expect, and only release builds are split. Off, too, when -Pauraai.abi has
// already narrowed the build to one ABI, since there would be nothing left to split.
val buildingRelease: Boolean = gradle.startParameter.taskNames.any { it.contains("elease") }
val abiSplits: Boolean = providers.gradleProperty("auraai.abiSplits")
    .getOrElse("true").toBoolean() && devAbis.isEmpty() && buildingRelease

/**
 * Per-ABI offsets for the version code. Every APK of one release must carry a different version
 * code, and a 64-bit APK must outrank the 32-bit one it can replace, so the order here is the
 * order of preference — a device that can run several takes the highest.
 */
val abiVersionOffsets = mapOf(
    "armeabi-v7a" to 1,
    "x86" to 2,
    "x86_64" to 3,
    "arm64-v8a" to 4
)

// Release signing. The keystore and its passwords live in keystore.properties, which is outside
// version control: a key that has been published is no longer a key, and every future update of
// a published app has to be signed with the same one.
//
// Parsed by hand rather than with java.util.Properties: that format treats a backslash as an
// escape, so a password containing one is silently read as something else, and the build fails
// with "keystore password was incorrect" while the file plainly holds the right password.
// Values are trimmed, so a password must not begin or end with a space.
val keystoreProperties: Map<String, String> = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.filterNot { it.isBlank() || it.trimStart().startsWith("#") }
    ?.mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.take(separator).trim() to line.substring(separator + 1).trim()
    }
    ?.toMap()
    .orEmpty()
val hasKeystore = keystoreProperties["storeFile"] != null

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

    splits {
        abi {
            isEnable = abiSplits
            reset()
            include(*abiVersionOffsets.keys.toTypedArray())
            // Kept so there is still one APK that installs anywhere, which is what side-loading
            // and "send it to someone" need.
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getValue("storeFile"))
                storePassword = keystoreProperties["storePassword"]
                keyAlias = keystoreProperties["keyAlias"]
                keyPassword = keystoreProperties["keyPassword"]
            }
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
            signingConfig = when {
                // A real key wins over the development convenience, so a machine that has one
                // never accidentally publishes a debug-signed build.
                hasKeystore -> signingConfigs.getByName("release")
                signReleaseWithDebugKey -> signingConfigs.getByName("debug")
                else -> null
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

if (signReleaseWithDebugKey && !hasKeystore) {
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

// The version code of a split APK has to be unique, and the universal one has to stay below the
// per-ABI APKs so that a device capable of both prefers its own architecture.
androidComponents {
    onVariants { variant ->
        for (output in variant.outputs) {
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            val offset = abi?.let { abiVersionOffsets[it] } ?: 0
            val base = output.versionCode.orNull ?: 1
            output.versionCode.set(base * 10 + offset)
        }
    }
}
