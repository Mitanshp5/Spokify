pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Required by the vendored extractor (com.github.TeamNewPipe:nanojson is a JitPack artifact).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SideA"

include(":app")

// Vendored NewPipe-derived extractor, reused as a source build.
// Group/name/version come from third_party/LevyraExtractor/build.gradle defaults.
includeBuild("third_party/LevyraExtractor")
