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
        maven("https://s3.amazonaws.com/repo.commonsware.com")
        maven("https://jitpack.io")
    }
}

// Composite build: use vendored Readium source instead of published artifacts
includeBuild("readium-kit")

rootProject.name = "EBookReader"
include(":app")
