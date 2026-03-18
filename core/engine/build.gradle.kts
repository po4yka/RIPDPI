import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.serialization")
    id("ripdpi.android.rust-native")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.engine"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logcat)
    implementation(libs.rustls.platform.verifier)

    testImplementation(libs.bundles.unit.test)
}
