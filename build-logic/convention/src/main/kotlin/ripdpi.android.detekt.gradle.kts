import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    id("io.gitlab.arturbosch.detekt")
}

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    // Baselines are permanently disabled — all violations must be fixed in code.
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/main/java",
            "src/test/kotlin",
            "src/test/java",
        ),
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
    exclude("**/build/**", "**/generated/**", "**/jni/**", "**/cpp/**")
}

dependencies {
    "detektPlugins"(project(":quality:detekt-rules"))
    "detektPlugins"(versionCatalogs.named("libs").findLibrary("detekt-compose-rules").get())
}
