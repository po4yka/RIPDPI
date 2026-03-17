import com.android.build.api.dsl.LibraryExtension
import com.google.devtools.ksp.gradle.KspExtension

plugins {
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.serialization")
    alias(libs.plugins.ksp)
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.diagnostics.data"
}

extensions.configure<KspExtension> {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.bundles.room)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.bundles.unit.test)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.room.testing)
}
