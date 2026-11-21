import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop")
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)
                
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
                
                implementation(libs.google.code.gson)
                // Analysis only, for mp3, and the same decoder the Android app uses. The platform
                // decoders disagree with each other by tens of milliseconds on where an mp3 frame
                // sits, which is enough to make an embedding from one device useless on the other.
                implementation(libs.jlayer)
                implementation(libs.jcodec)
            }
        }
        
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.vlcj)
                implementation(libs.jaudiotagger)
                implementation(libs.onnxruntime.jvm)
                // MPRIS lives on the session bus; the native-unixsocket transport uses the
                // JDK's own unix domain sockets, so it needs no JNI and no extra native library.
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport.unixsocket)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "music.ai.recommend.MainKt"

        // The MPRIS DesktopEntry property has to name the .desktop file this build actually
        // installs, and only the build knows it: jpackage joins the package name and the app name
        // whenever they differ, so "aimusic" + "AiMusic" ships as aimusic-AiMusic.desktop.
        // Passing it in keeps the packaging detail out of the application code, which falls back
        // to a plain name when run straight from Gradle.
        jvmArgs("-Daimusic.desktopEntry=aimusic-AiMusic")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AiMusic"
            packageVersion = "1.1.1"
            description = "Music player with CLAP audio search and smart albums"
            vendor = "Nikita"

            // The first four are what `suggestRuntimeModules` derives from the bytecode.
            // jdk.charsets is not among them and has to be added by hand: the library scanner
            // repairs mis-tagged ID3 text with Charset.forName("Windows-1251"), a lookup by name
            // that no static analysis can see. Leaving it out costs nothing under `run`, which
            // uses the full JDK, and turns every Cyrillic tag into mojibake once installed.
            modules("java.instrument", "java.sql", "jdk.security.auth", "jdk.unsupported", "jdk.charsets")


            linux {
                // Debian package names are lowercase; this is also what the installed tree under
                // /opt and the .deb file itself are named after.
                packageName = "aimusic"
                debMaintainer = "sharap.software@gmail.com"
                appCategory = "Audio"
                menuGroup = "Audio"
                iconFile.set(project.file("packaging/aimusic.png"))
                shortcut = true
            }
        }
    }
}

/**
 * Puts the package's dependencies right once jpackage is done.
 *
 * Two problems, one fix. libVLC is dlopen-ed through JNA and ffmpeg is spawned per track, so
 * neither appears in any dependency scan and the package would install and then silently play
 * nothing. And jpackage derives the rest from the build machine, where Ubuntu 24.04 renamed half
 * of them for the 64-bit time_t transition (libasound2 -> libasound2t64) while Debian 12 kept the
 * old names — so a package built on one does not install on the other at all.
 *
 * The script writes the missing names in and gives every library an alternative through `|`, which
 * is what dpkg has for exactly this. It rewrites only the control member, so the 170 MB of data is
 * never unpacked and repacked.
 *
 * In afterEvaluate because the Compose plugin registers its packaging tasks after this script is
 * evaluated.
 */
afterEvaluate {
    val portableDeb = tasks.register<Exec>("portableDeb") {
        description = "Makes the .deb installable on both Debian and Ubuntu."
        group = "compose desktop"
        val binaries = layout.buildDirectory.dir("compose/binaries")
        commandLine("bash", rootDir.resolve("tools/portable-deb.sh").absolutePath, binaries.get().asFile.absolutePath)
        onlyIf { binaries.get().asFile.isDirectory }
    }

    tasks.named("packageDeb") { finalizedBy(portableDeb) }
}
