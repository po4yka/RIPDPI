import com.android.build.api.dsl.LibraryExtension
import com.google.devtools.ksp.gradle.KspExtension

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    alias(libs.plugins.ksp)
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.data"
}

extensions.configure<KspExtension> {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:data:model"))
    api(project(":core:data:catalog"))
    api(project(":core:data:settings"))
    api(project(":core:data:runtime-state"))

    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.androidx.datastore)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.room.testing)
}
