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
    implementation(project(":core:detection"))
    implementation(project(":core:diagnostics-data"))
    implementation(project(":core:engine"))
    implementation(project(":core:engine-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kermit)
    implementation(libs.okhttp)
    implementation(libs.brotli.dec)
    implementation(libs.zstd.jni)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.okhttp.mockwebserver)
}
