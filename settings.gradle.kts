buildCache {
    local {
        // Gradle 9 manages local cache cleanup automatically.
        // Add remote { } here to enable a shared remote cache (e.g. for CI).
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RipDpi"
include(":app")
include(":core:data")
include(":core:diagnostics")
include(":core:diagnostics-data")
include(":core:engine")
include(":core:service")
include(":quality:detekt-rules")
