plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.kotlin.compose.get()}")
    compileOnly("com.google.protobuf:protobuf-gradle-plugin:${libs.versions.protobuf.plugin.get()}")
    compileOnly(libs.plugins.detekt.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
    compileOnly(libs.plugins.ktlint.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
}
