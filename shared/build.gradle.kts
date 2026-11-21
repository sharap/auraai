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
        }
        commonTest.dependencies {
            implementation(libs.junit)
        }
    }
}
