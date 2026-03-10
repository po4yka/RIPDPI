plugins {
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.protobuf")
    id("ripdpi.android.serialization")
}

android {
    namespace = "com.poyka.ripdpi.core.data"
}

dependencies {
    api(libs.protobuf.javalite)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logcat)

    testImplementation(libs.bundles.unit.test)
}
