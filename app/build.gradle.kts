import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("ripdpi.android.application")
    id("ripdpi.android.coverage")
    id("ripdpi.android.compose")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.roborazzi")
}

val releaseStoreFilePath = providers.environmentVariable("RIPDPI_SIGNING_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("RIPDPI_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("RIPDPI_SIGNING_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("RIPDPI_SIGNING_KEY_PASSWORD")

extensions.configure<ApplicationExtension> {
    namespace = "com.poyka.ripdpi"

    defaultConfig {
        applicationId = "com.poyka.ripdpi"
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "com.poyka.ripdpi.HiltTestRunner"
    }

    signingConfigs {
        releaseStoreFilePath.orNull?.let { configuredStoreFile ->
            create("release") {
                storeFile = file(configuredStoreFile)
                storePassword = releaseStorePassword.orNull
                keyAlias = releaseKeyAlias.orNull
                keyPassword = releaseKeyPassword.orNull
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}-debug\"")
            enableAndroidTestCoverage = true
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

    implementation(libs.bundles.lifecycle.app)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.test.core.ktx)

    // Modules
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:engine"))
    implementation(project(":core:service"))

    // Proto DataStore
    implementation(libs.androidx.datastore)

    implementation(libs.logcat)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.okhttp)

    testImplementation(libs.bundles.unit.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(project(":core:diagnostics-data"))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(project(":core:diagnostics-data"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
