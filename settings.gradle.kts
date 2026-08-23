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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Nextcloud's Single-Sign-On library (login via the Nextcloud
        // Files app) is published to JitPack only — there is no Maven
        // Central coordinate for it. Scoped to that one group so a typo
        // in any other dependency can't silently resolve from a
        // build-on-demand repository.
        maven {
            setUrl("https://jitpack.io")
            content { includeGroup("com.github.nextcloud") }
        }
    }
}

rootProject.name = "NcCollectives"
include(":app")
