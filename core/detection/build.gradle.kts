import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.detection"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":xray-protos"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.grpc.okhttp)

    testImplementation(libs.bundles.unit.test)
}
