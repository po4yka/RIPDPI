plugins {
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.serialization")
    id("ripdpi.android.rust-native")
}

android {
    namespace = "com.poyka.ripdpi.core.engine"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.unit.test)
}
