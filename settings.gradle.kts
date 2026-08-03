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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // apksig is published to Google's Maven only; Maven Central carries an
        // ancient 2.3.0 that must not be picked up by accident.
        google()
        mavenCentral()
    }
}

rootProject.name = "pwagen"

include(":app")
include(":shell")
include(":config")
