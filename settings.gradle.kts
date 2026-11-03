rootProject.name = "Kharcha"

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
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// `-Pkharcha.coreOnly=true` builds only the platform-independent :core module.
// Used where the Android SDK / Google Maven are unavailable (e.g. sandboxed CI or
// cloud sessions). Normal development builds everything.
val coreOnly = providers.gradleProperty("kharcha.coreOnly").orNull == "true"

include(":core")
if (!coreOnly) {
    include(":shared")
    include(":androidApp")
}
