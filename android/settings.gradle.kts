pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "healthhub"

include(":app")

// core:* modules hold no feature logic and never depend on a feature module.
include(":core:model")
include(":core:designsystem")
include(":core:ui")
include(":core:navigation")
include(":core:database")
include(":core:network")
include(":core:healthconnect")
include(":core:telemetry")
include(":core:sync")
include(":core:devcontrol")

// feature:* modules depend on core:*, never on each other. A new capability — social
// features in particular — is added here without touching anything above.
include(":feature:auth")
include(":feature:feed")
include(":feature:activity")
include(":feature:sync")
include(":feature:settings")
include(":feature:sources")
include(":feature:health")
// Deliberately trivial. It exists to keep SC-012 honest — see feature/about.
include(":feature:about")
