import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.serialization")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.pcap"
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:data:model"))
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.unit.test)
}
