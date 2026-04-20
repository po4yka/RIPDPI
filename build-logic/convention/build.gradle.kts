plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.kotlin.compose.get()}")
    compileOnly(
        libs.plugins.kotlin.serialization
            .map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" },
    )
    compileOnly(libs.plugins.hilt.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    compileOnly(libs.plugins.ksp.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    compileOnly(libs.plugins.detekt.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    compileOnly(libs.plugins.ktlint.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    compileOnly(libs.plugins.roborazzi.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
