import com.android.build.api.dsl.LibraryExtension
import java.util.Properties

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
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

val masquePrivacyPassProviderUrl =
    localOrEnv(
        "masque.privacyPassProviderUrl",
        "RIPDPI_MASQUE_PRIVACY_PASS_PROVIDER_URL",
    )
val masquePrivacyPassProviderAuthToken =
    localOrEnv(
        "masque.privacyPassProviderAuthToken",
        "RIPDPI_MASQUE_PRIVACY_PASS_PROVIDER_AUTH_TOKEN",
    )

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.service"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField(
            "String",
            "MASQUE_PRIVACY_PASS_PROVIDER_URL",
            "\"${masquePrivacyPassProviderUrl.orEmpty()}\"",
        )
        buildConfigField(
            "String",
            "MASQUE_PRIVACY_PASS_PROVIDER_AUTH_TOKEN",
            "\"${masquePrivacyPassProviderAuthToken.orEmpty()}\"",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics-data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kermit)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.security.crypto)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:detection"))
}
