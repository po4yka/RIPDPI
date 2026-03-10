plugins {
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
}

android {
    namespace = "com.poyka.ripdpi.core.service"
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logcat)

    testImplementation(libs.bundles.unit.test)
}
