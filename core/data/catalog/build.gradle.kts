import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.quality")
    id("ripdpi.android.serialization")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.data.catalog"
}

dependencies {
    api(project(":core:data:model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.unit.test)
}
