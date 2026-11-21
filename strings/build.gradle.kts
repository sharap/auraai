// Named once: the generated accessor's package and the directory the runtime reads are the same
// string, and they are read in two places in this file.
val RESOURCE_PACKAGE = "music.ai.recommend.strings.resources"

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

/**
 * Lays this module's words out the way the Android runtime looks for them, and hands the result to
 * the packaging.
 *
 * None of this should be necessary. The Compose resources plugin creates a task for exactly this
 * — `copyAndroidMainComposeResourcesToAndroidAssets` — but with AGP's KMP library plugin (AGP
 * 9.3.1, Compose 1.11.1) it leaves that task without an output directory and registers it with
 * nobody, so the AAR goes out carrying no words at all. Its own class is `internal`, so it cannot
 * even be configured from here; hence a task of our own doing the same copy.
 *
 * It was invisible until a phone asked for a string, because the desktop target *is* assembled
 * properly: the words are read from the jar there and the only build that reads them from an APK
 * was the one made after this module appeared. What the phone threw was "Missing resource with
 * path: composeResources/music.ai.recommend.strings.resources/values/strings.commonMain.cvr", on
 * the first wave mood or smart album name — which is to say, on opening the playlists screen.
 *
 * The layout matters: the runtime asks for `composeResources/<the resource package>/values/…`,
 * while the prepared files sit in `composeResources/values/…`, without the package. The package
 * level is what this adds.
 */
abstract class ComposeResourcesForAndroid : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prepared: ConfigurableFileCollection

    @get:Input
    abstract val resourcePackage: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        val target = "composeResources/${resourcePackage.get()}"
        files.sync {
            into(outputDirectory)
            from(prepared) { into(target) }
        }
    }
}

val composeResourcesForAndroid = tasks.register<ComposeResourcesForAndroid>("composeResourcesForAndroid") {
    description = "Copies the Compose resources into the layout the Android runtime expects."
    // The producing task itself, not the path it writes to: that is what carries the dependency,
    // and Gradle refuses to read another task's output without one.
    prepared.from(tasks.named("prepareComposeResourcesTaskForCommonMain"))
    resourcePackage.set(RESOURCE_PACKAGE)
    outputDirectory.set(layout.buildDirectory.dir("androidComposeResources"))
}

/**
 * The words, handed to whoever packages an APK rather than packaged here.
 *
 * AGP's variant API has `sources.assets` for exactly this, and an android variant of the KMP
 * library plugin does not have it — which is also why the plugin's own copy task was left
 * unconfigured. So the directory travels as an artifact and `:app` adds it to its assets, where
 * the classic application plugin does support it.
 */
val androidComposeResources: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts.add(androidComposeResources.name, composeResourcesForAndroid.flatMap { it.outputDirectory }) {
    builtBy(composeResourcesForAndroid)
}

compose.resources {
    // Pinned, not derived from the module's name; see the desktop module for what that cost once.
    packageOfResClass = RESOURCE_PACKAGE
    publicResClass = true
}
