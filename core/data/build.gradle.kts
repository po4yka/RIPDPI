plugins {
    id("ripdpi.android.library")
    id("ripdpi.android.protobuf")
}

android {
    namespace = "com.poyka.ripdpi.core.data"
}

dependencies {
    api(libs.protobuf.javalite)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}
