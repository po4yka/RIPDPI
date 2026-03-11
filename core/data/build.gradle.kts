import com.android.build.api.dsl.LibraryExtension
import com.google.devtools.ksp.gradle.KspExtension

plugins {
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.protobuf")
    id("ripdpi.android.serialization")
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
    api(libs.protobuf.javalite)
    implementation(libs.androidx.datastore)
    implementation(libs.bundles.room)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logcat)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.bundles.unit.test)
}
