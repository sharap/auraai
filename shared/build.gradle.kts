import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

/**
 * What both ports are: the sync format, the excerpt rule, the decoders, the analysis.
 *
 * It exists because the alternative had been tried. The same 9091 lines lived twice, once per
 * repository, kept in step by a script that diffed 56 files and by whoever remembered to run it —
 * and six files had drifted anyway, silently, because nothing fails when two copies disagree. Here
 * there is one file, and nothing to compare it against.
 *
 * Two targets and no third: `androidTarget` for the phone and `jvm("desktop")` for the desktop,
 * named to match the source sets the desktop port already had. Both compile to Java 11 bytecode —
 * the Android app's own level, since bytecode it cannot read would make this module useless to the
 * half of the project that needs it most.
 *
 * What may not come in here: anything platform-bound. No Room, no MediaStore, no Android
 * framework, no AWT. Where the common code needs the platform it asks through an interface or an
 * `expect` declaration, and each port answers in its own source set. That rule is the whole reason
 * the module can exist at all.
 */
kotlin {
    android {
        // No host test compilation here, deliberately. The AGP 9 multiplatform plugin leaves it off
        // until asked, and asking would buy a second run of the same tests: both targets compile
        // the same files out of commonMain, and an Android unit test runs on the host JVM, so it
        // exercises no Android API that the desktop run does not. The moment that stops being true
        // — the first `expect` in here with an Android `actual` behind it — is the moment to turn
        // it on, because then the two runs would be running different code.
        namespace = "music.ai.recommend.shared"
        compileSdk = 37
        minSdk = 23
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    sourceSets {
        commonMain.dependencies {
            // Gson rather than kotlinx.serialization: the record format is already written against
            // it on both sides, and changing how records are parsed is not part of moving them.
            implementation(libs.google.code.gson)
            // VectorCourier bridges a blocking socket conversation to the suspending coordinator.
            implementation(libs.kotlinx.coroutines.core)
            // The shared decoders: mp3 through JLayer, m4a through the AAC decoder jcodec carries.
            // Both ports already depended on these, because both ports carried the decoders.
            implementation(libs.jlayer)
            implementation(libs.jcodec)
        }
        commonTest.dependencies {
            // JUnit 4 rather than kotlin-test, which is what a multiplatform module would normally
            // use. Both targets are JVM, so it resolves for each of them — the same reason the JDK
            // is visible in commonMain. What that bought was the move itself: the eighteen sync
            // tests came over from the Android app without a single edit, which can be proved by
            // diffing them against what passed before, while converting them to kotlin-test would
            // have meant reordering a message argument in each file and arguing about whether each
            // test still tested the same thing. Adding a target that is not JVM would mean
            // switching, and would say so by failing to compile rather than by passing quietly.
            implementation(libs.junit)
        }
    }
}
