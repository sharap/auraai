import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask

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
                // What both ports are; see shared/build.gradle.kts.
                implementation(project(":shared"))
                // The words both interfaces show; see strings/build.gradle.kts.
                implementation(project(":strings"))
                implementation(libs.jlayer)
                implementation(libs.jcodec)
            }
        }
        
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
            // The committed audio fixtures live once, beside the shared module's own tests, and
            // this port's tests read them off the classpath. VectorRoundTripTest needs a real mp3
            // to put in a library — it is the test that proves a vector made here is accepted
            // there — and a second copy of the same 300 KB is how the repository ended up carrying
            // 1.5 MB of identical audio twice.
            resources.srcDir(rootProject.file("shared/src/commonTest/resources"))
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

/**
 * The package the generated `Res` class lands in, pinned rather than derived.
 *
 * Without this it is made from the module's name, so renaming the module renames a package the
 * code imports — and, worse, renames a path that only exists as a string: the tokenizer files are
 * read out of the jar by `composeResources/<this package>/files/...`, which no compiler checks.
 * Renaming `composeApp` to `desktop` broke the import loudly and would have broken the string
 * silently, at the first text search rather than at the build. Pinned to the code's own package,
 * which does not depend on where the module sits.
 */
compose.resources {
    packageOfResClass = "music.ai.recommend.resources"
}

compose.desktop {
    application {
        mainClass = "music.ai.recommend.MainKt"

        // The MPRIS DesktopEntry property has to name the .desktop file this build actually
        // installs, and only the build knows it: jpackage joins the package name and the app name
        // whenever they differ, so "aimusic" + "AiMusic" ships as aimusic-AiMusic.desktop — the
        // same name in the .deb and in the .rpm, checked in both.
        // Passing it in keeps the packaging detail out of the application code, which falls back
        // to a plain name when run straight from Gradle.
        jvmArgs("-Daimusic.desktopEntry=aimusic-AiMusic")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
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
                // Package names are lowercase on both sides; this is also what the installed
                // tree and the package file itself are named after.
                packageName = "aimusic"
                /*
                 * Into the /usr tree rather than jpackage's default /opt, which is what makes the
                 * application startable by name: given a directory inside /usr, jpackage puts the
                 * launcher in /usr/bin and everything else in /usr/lib/aimusic, and /usr/bin is
                 * on everyone's PATH. Under /opt there is nothing on the PATH at all and no way
                 * to add one — the scripts that would do it come from a template this build
                 * cannot reach.
                 *
                 * Both formats are built the same way by jpackage, but only the rpm has been
                 * tested here; this machine cannot build a .deb at all.
                 */
                installationPath = "/usr"
                debMaintainer = "sharap.software@gmail.com"
                // "License:" of the generated .spec. Fedora wants an SPDX identifier, and this
                // is the one in LICENSE: version 3 of the GPL or, at your option, any later.
                rpmLicenseType = "GPL-3.0-or-later"
                appCategory = "Audio"
                menuGroup = "Audio"
                iconFile.set(project.file("packaging/aimusic.png"))
                shortcut = true
            }
        }
    }
}

/**
 * Which JDK the packaging tools run from, and why it is not the one that compiles the code.
 *
 * jlink builds the runtime that goes inside the package, and it needs a JDK it can read the
 * modules out of. There are two ways: a `jmods` directory, or — since JDK 24 — the running image
 * itself, which it accepts only if nothing in it has been touched since it was built. Fedora's
 * openjdk has neither: the jmods are a separate package that is not installed by default, and
 * `conf/security/java.security` is patched for the system crypto policy, so the second way is
 * refused too. jlink stops with "java.security has been modified" and no flag overrides it.
 *
 * So packaging asks for a JDK of its own through Gradle's toolchains, the mechanism this build
 * already provisions JDKs with. Two things follow, both of them wanted: the runtime handed to
 * other people is a stock upstream one rather than whatever this machine's distribution ships,
 * and the package does not come out different depending on which machine built it.
 *
 * Lazily, through a provider: it is read when a packaging task runs, so an ordinary `build` —
 * here or on CI, which never packages — provisions nothing.
 */
val packagingJdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}.map { it.metadata.installationPath.asFile.absolutePath }

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
    // Inside afterEvaluate, and not beside the declaration above, for an ordering reason: the
    // plugin sets these itself when it registers the tasks, and whichever assignment is added
    // last is the one that holds. Added from here, ours is.
    //
    // checkRuntime probes a JDK and writes down what the later tasks package; told nothing, it
    // reports on this machine's JDK while jlink reads another one.
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach {
        jdkHome.set(packagingJdk)
    }
    // jlink and jpackage, for every format and for the plain app image.
    tasks.withType<AbstractJvmToolOperationTask>().configureEach {
        javaHome.set(packagingJdk)
    }

    val portableDeb = tasks.register<Exec>("portableDeb") {
        description = "Makes the .deb installable on both Debian and Ubuntu."
        group = "compose desktop"
        val binaries = layout.buildDirectory.dir("compose/binaries")
        commandLine(
            "bash",
            // This module's own tools, not the repository root's: the desktop port moved in
            // here with them, and the root is now shared with the Android app.
            project.file("tools/portable-deb.sh").absolutePath,
            binaries.get().asFile.absolutePath
        )
        onlyIf { binaries.get().asFile.isDirectory }
    }

    tasks.named("packageDeb") { finalizedBy(portableDeb) }

    /*
     * Two things the Compose DSL has no field for, passed straight to jpackage. Both formats, in
     * both flavours, because a package that comes out of the release flavour is the same package.
     *
     * First: the lowercase command. See packaging/aimusic-launcher.properties for what it is and
     * why there are two launchers rather than one.
     *
     * Second, rpm only: the two dependencies no scan of the binaries can find, because libVLC
     * arrives through JNA and ffmpeg is run as a process. This format needs no repacking
     * afterwards the way the .deb does — jpackage has a flag for it.
     *
     * They are named as capabilities rather than as packages, because package names are exactly
     * what went wrong with the .deb — the same two are written into it by tools/portable-deb.sh
     * in Debian's spelling, so change one list and look at the other. libvlc is vlc-libs on
     * Fedora and libvlc5 on openSUSE, and the ffmpeg binary is ffmpeg-free in Fedora's own
     * repositories and ffmpeg in RPM Fusion; a soname and a path under /usr/bin mean the same
     * thing everywhere, and rpm resolves both. The plugins have no capability to point at, so
     * that one gets alternatives instead — rpm's own answer to dpkg's `|`.
     */
    val terminalLauncher = project.file("packaging/aimusic-launcher.properties")
    for (format in listOf("Deb", "ReleaseDeb", "Rpm", "ReleaseRpm")) {
        tasks.named<AbstractJPackageTask>("package$format") {
            inputs.file(terminalLauncher)
            freeArgs.addAll("--add-launcher", "aimusic=${terminalLauncher.absolutePath}")
            if (format.endsWith("Rpm")) {
                freeArgs.addAll(
                    "--linux-package-deps",
                    // Quoted here by hand: free arguments reach jpackage's argument file exactly
                    // as given, and that file is split on whitespace unless a value is quoted.
                    listOf(
                        "libvlc.so.5()(64bit)",
                        "/usr/bin/ffmpeg",
                        "(vlc-plugins-base or vlc)",
                    ).joinToString(", ", prefix = "\"", postfix = "\"")
                )
            }
        }
    }
}

// The shared model types are stable, and only this side's Compose compiler can be told so: see
// compose-stability.conf at the root, which both UI modules read.
composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose-stability.conf")
    )
}

// Switches these tests read, forwarded into the test JVM, which is a different process from the
// one Gradle runs in. Without this, `-Dflac.limit=5` set what Gradle saw and the tests went on
// reading their defaults, so the knob had never once worked. `aura.music` points the
// decoder-against-ffmpeg comparisons at a folder other than ~/Music, which is also how the
// "nothing to compare, skip" path gets exercised on a machine that does have music.
tasks.withType<Test>().configureEach {
    for (name in listOf("flac.limit", "m4a.limit", "aura.music")) {
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}
