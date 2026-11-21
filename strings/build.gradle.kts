plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    // Required by the resources plugin since Compose Multiplatform 1.6.10, which also means the
    // Compose runtime is on this module's classpath. That is the line this module exists to draw:
    // the runtime belongs where words and resources are, and not in :shared, which holds the sync
    // protocol and the decoders.
    alias(libs.plugins.kotlin.compose)
}

/**
 * The words both interfaces show, in one place, so that they cannot differ.
 *
 * It exists because of what was found when the two code bases were merged: the smart albums were
 * named by thirty-five strings on each side, and the two sides agreed — except that the desktop's
 * were English literals in the source and the phone's were translated resources. So the phone said
 * «Транс» and the desktop said "Trance", and nothing but attention kept the English halves from
 * drifting apart as well.
 *
 * Compose Resources rather than Android's own, because only one of the two platforms can read those.
 * This is also why the module exists at all instead of the strings living in :shared: that module
 * holds the sync protocol, the decoders and the analysis, and a UI resource runtime has no business
 * there. Here it does — this module is nothing but words.
 */
kotlin {
    android {
        namespace = "music.ai.recommend.strings"
        compileSdk = 37
        minSdk = 23
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // The generated accessor and the lookup by name come from the resources library; the
            // runtime is what the compiler plugin above insists on. No foundation, no material,
            // no ui: this module draws nothing.
            api(compose.components.resources)
            api(compose.runtime)
        }
    }
}

compose.resources {
    // Pinned, not derived from the module's name; see the desktop module for what that cost once.
    packageOfResClass = "music.ai.recommend.strings.resources"
    publicResClass = true
}
