pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (offline Thai OCR) is distributed via JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.adaptech-cz.*") }
        }
    }
}

rootProject.name = "Shreddro"
include(":core")
include(":app")
