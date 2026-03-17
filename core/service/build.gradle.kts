import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.service"
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
    implementation(libs.logcat)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.kotlinx.serialization.json)
}
