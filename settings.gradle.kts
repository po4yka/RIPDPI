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

        // rustls-platform-verifier ships its Android CertificateVerifier class
        // as a prebuilt AAR inside a local Maven repo embedded in the Cargo crate.
        maven {
            val cargoHome = File(
                System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
            )
            val registryBase = cargoHome.resolve("registry/src")
            val mavenDir = registryBase.listFiles()
                ?.flatMap { index ->
                    index.listFiles()
                        ?.filter { it.name.startsWith("rustls-platform-verifier-android-") }
                        ?.toList() ?: emptyList()
                }
                ?.maxByOrNull { it.name }
                ?.resolve("maven")
            url = uri(mavenDir ?: registryBase)
            content {
                includeGroup("rustls")
            }
        }
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
