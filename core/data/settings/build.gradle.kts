import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.serialization")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.data.settings"

    testFixtures {
        enable = true
    }
}

dependencies {
    api(project(":core:data:model"))
    api(project(":core:data:catalog"))
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.androidx.datastore)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.androidx.test.core.ktx)
}
