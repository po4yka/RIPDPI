import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    id("ripdpi.android.application")
    id("ripdpi.android.coverage")
    id("ripdpi.android.compose")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.roborazzi")
    id("ripdpi.android.serialization")
}

val localProps =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use(::load)
    }

fun localOrEnv(
    propKey: String,
    envKey: String,
): String? = localProps.getProperty(propKey) ?: providers.environmentVariable(envKey).orNull

val releaseStoreFilePath = localOrEnv("signing.storeFile", "RIPDPI_SIGNING_STORE_FILE")
val releaseStorePassword = localOrEnv("signing.storePassword", "RIPDPI_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = localOrEnv("signing.keyAlias", "RIPDPI_SIGNING_KEY_ALIAS")
val releaseKeyPassword = localOrEnv("signing.keyPassword", "RIPDPI_SIGNING_KEY_PASSWORD")

extensions.configure<ApplicationExtension> {
    namespace = "com.poyka.ripdpi"

    defaultConfig {
        applicationId = "com.poyka.ripdpi"
        versionCode = 7
        versionName = "0.0.7"

        testInstrumentationRunner = "com.poyka.ripdpi.HiltTestRunner"
        testInstrumentationRunnerArguments["clearPackageData"] =
            providers.gradleProperty("ripdpi.androidTestClearPackageData").orElse("true").get()
    }

    signingConfigs {
        releaseStoreFilePath?.let { configuredStoreFile ->
            create("release") {
                storeFile = file(configuredStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }

    val gitCommit: String =
        providers
            .exec { commandLine("git", "rev-parse", "--short", "HEAD") }
            .standardOutput
            .asText
            .map { it.trim() }
            .orElse("unavailable")
            .get()

    val nativeLibVersion: String =
        providers
            .provider {
                rootProject.file("native/rust/Cargo.toml").useLines { lines ->
                    lines
                        .firstOrNull { it.trimStart().startsWith("version") && it.contains("\"") }
                        ?.substringAfter('"')
                        ?.substringBefore('"')
                        ?: "unavailable"
                }
            }.get()

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "NATIVE_LIB_VERSION", "\"$nativeLibVersion\"")
        }
        debug {
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}-debug\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "NATIVE_LIB_VERSION", "\"$nativeLibVersion\"")
            enableAndroidTestCoverage = true
        }
        create("benchmark") {
            initWith(getByName("release"))
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}-bench\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "NATIVE_LIB_VERSION", "\"$nativeLibVersion\"")
        }
    }

    // https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)

    implementation(libs.bundles.lifecycle.app)
    implementation(libs.androidx.lifecycle.process)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.runtime.tracing)
    debugImplementation(libs.androidx.test.core.ktx)

    // Modules
    implementation(project(":core:data"))
    implementation(project(":core:detection"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:engine"))
    implementation(project(":core:service"))

    // Proto DataStore
    implementation(libs.androidx.datastore)

    implementation(libs.kermit)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    testImplementation(libs.bundles.unit.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(project(":core:diagnostics-data"))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(project(":core:diagnostics-data"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
