pluginManagement {
    repositories {
        // SIMPLIFIED: Remove the 'content' block to allow full access
        google()
        mavenCentral()
        gradlePluginPortal()
        // Allow resolving community artifacts (e.g., Jellyfin media3 ffmpeg artifacts on JitPack)
        maven {
            url = uri("https://jitpack.io")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack for community builds not hosted on Maven Central
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "PlayIT"
include(":app")
