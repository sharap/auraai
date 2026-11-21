pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AiMusic"
include(":app")

// The desktop port. One repository now, because two of them meant 9091 lines kept in step by hand
// and a script that checked they had not drifted — and six files had drifted anyway.
include(":desktop")

// What both of them are. See shared/build.gradle.kts for what may and may not live here.
include(":shared")
