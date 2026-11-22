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
    // Since 0.14 only the settings plugin makes every project contribute its module edges.
    id("dev.iurysouza.modulegraph.settings") version "0.15.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Maven Central rate-limits these large Robolectric downloads on shared CI runners.
        exclusiveContent {
            forRepository {
                maven {
                    name = "RobolectricArtifactsMirror"
                    url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
                }
            }
            filter {
                includeModule("org.robolectric", "android-all-instrumented")
                includeModule("org.robolectric", "nativeruntime-dist-compat")
            }
        }
    }
}

rootProject.name = "RipDpi"
include(":app")
include(":core:data")
include(":core:data:catalog")
include(":core:data:model")
include(":core:data:runtime-state")
include(":core:data:settings")
include(":core:diagnostics")
include(":core:diagnostics-data")
include(":core:detection")
include(":core:engine")
include(":core:engine-api")
include(":core:pcap-export")
include(":core:service")
include(":quality:detekt-rules")
include(":xray-protos")
include(":baselineprofile")
include(":testing:socket-bind-probe")
include(":testing:awg-interop")
