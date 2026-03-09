plugins {
    id("ripdpi.android.application")
    id("ripdpi.android.compose")
}

android {
    namespace = "com.poyka.ripdpi"

    defaultConfig {
        applicationId = "com.poyka.ripdpi"
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}\"")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}-debug\"")
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
    implementation(libs.androidx.appcompat)

    implementation(libs.bundles.lifecycle.app)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Modules
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":core:service"))

    // Proto DataStore
    implementation(libs.androidx.datastore)

    testImplementation(libs.bundles.unit.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
