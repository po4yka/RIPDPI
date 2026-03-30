import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.diagnostics.catalog")
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.serialization")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.diagnostics"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics-data"))
    implementation(project(":core:engine"))
    testImplementation(project(":core:service"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kermit)

    testImplementation(libs.bundles.unit.test)
}
