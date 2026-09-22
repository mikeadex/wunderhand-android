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
    }
}

rootProject.name = "wunderhand"

// :core and :network are plain Kotlin on the JVM, so their tests run in
// seconds with no emulator — the same reason the iOS app has WunderhandKit.
include(":app", ":core", ":network", ":design")
// Generates app/src/release/generated/baselineProfiles/ on an emulator; never shipped.
include(":baselineprofile")
