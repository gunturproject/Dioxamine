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
        maven { url = uri("https://jitpack.io") }
        mavenLocal()
    }
}

rootProject.name = "Dioxamine"
include(":app")
include(":app-logger")
include(":adb-kt")
project(":adb-kt").projectDir = file("adb-kt/lib")
include(":fastboot-kt")
project(":fastboot-kt").projectDir = file("fastboot-kt/lib")
include(":terminal-emulator")
include(":terminal-view")
