import org.gradle.api.Project
import org.gradle.testing.jacoco.tasks.JacocoReport

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.roborazzi) apply false
    jacoco
}

val coverageModules =
    listOf(
        project(":app"),
        project(":core:data"),
        project(":core:diagnostics"),
        project(":core:engine"),
        project(":core:service"),
    )

val kotlinCoverageExcludes =
    listOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*_Factory.class",
        "**/*_Factory\$*.class",
        "**/*_MembersInjector.class",
        "**/Hilt_*.*",
        "**/*Hilt*.*",
        "**/*_HiltModules*.*",
        "**/*ComposableSingletons*.*",
        "**/*Preview*.*",
        "**/*\$serializer.class",
        "**/*\$Companion.class",
        "**/com/poyka/ripdpi/proto/**",
        "**/com/poyka/ripdpi/data/schemas/**",
    )

fun Project.kotlinDebugCoverageClasses() =
    files(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(kotlinCoverageExcludes)
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            exclude(kotlinCoverageExcludes)
        },
    )

fun Project.kotlinDebugCoverageExecutionData() =
    fileTree(layout.buildDirectory) {
        include("jacoco/testDebugUnitTest.exec")
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    }

tasks.register<JacocoReport>("kotlinCoverageReport") {
    group = "verification"
    description = "Aggregates Kotlin debug unit test JaCoCo reports across Android modules."
    dependsOn(coverageModules.map { "${it.path}:jacocoDebugUnitTestReport" })

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(
        coverageModules.flatMap { module ->
            listOf(
                module.file("src/main/java"),
                module.file("src/main/kotlin"),
            )
        },
    )
    classDirectories.setFrom(coverageModules.map { it.kotlinDebugCoverageClasses() })
    executionData.setFrom(coverageModules.map { it.kotlinDebugCoverageExecutionData() })
    onlyIf { executionData.files.any { it.exists() } }
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Runs aggregate Kotlin coverage reporting."
    dependsOn("kotlinCoverageReport")
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs detekt, ktlint, and Android Lint across all modules"
    dependsOn(
        subprojects.flatMap { it.tasks.matching { t -> t.name == "detekt" } },
        subprojects.flatMap { it.tasks.matching { t -> t.name == "lintDebug" } },
        subprojects.flatMap { it.tasks.matching { t -> t.name == "ktlintCheck" } },
    )
}

tasks.register("recordScreenshots") {
    group = "verification"
    description = "Records Roborazzi screenshot baselines for the app module"
    dependsOn(":app:recordRoborazziDebug")
}

tasks.register("verifyScreenshots") {
    group = "verification"
    description = "Verifies Roborazzi screenshot baselines for the app module"
    dependsOn(":app:verifyRoborazziDebug")
}
