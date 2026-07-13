import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    jacoco
}

val jacocoExcludes =
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

val coverageVariantName = if (project.path == ":app") "githubFullDebug" else "debug"
val coverageVariantTaskSuffix = coverageVariantName.replaceFirstChar(Char::uppercaseChar)
val coverageUnitTestTaskName = "test${coverageVariantTaskSuffix}UnitTest"
val transformedCoverageClasses =
    layout.buildDirectory.dir(
        "intermediates/classes/$coverageVariantName/" +
            "transform${coverageVariantTaskSuffix}ClassesWithAsm/dirs",
    )
val kotlinCoverageClasses =
    layout.buildDirectory.dir(
        "intermediates/built_in_kotlinc/$coverageVariantName/" +
            "compile${coverageVariantTaskSuffix}Kotlin/classes",
    )
val javaCoverageClasses =
    layout.buildDirectory.dir(
        "intermediates/javac/$coverageVariantName/" +
            "compile${coverageVariantTaskSuffix}JavaWithJavac/classes",
    )
val coverageClassDirectories =
    files(
        transformedCoverageClasses.map { transformedClasses ->
            if (transformedClasses.asFile.isDirectory) {
                listOf(transformedClasses.asFile)
            } else {
                listOf(kotlinCoverageClasses.get().asFile, javaCoverageClasses.get().asFile)
            }
        },
    ).asFileTree.matching {
        exclude(jacocoExcludes)
    }
val coverageExecutionData =
    fileTree(layout.buildDirectory) {
        include("jacoco/$coverageUnitTestTaskName.exec")
        include(
            "outputs/unit_test_code_coverage/${coverageVariantName}UnitTest/" +
                "$coverageUnitTestTaskName.exec",
        )
    }

val jacocoAgentEnabled =
    providers
        .gradleProperty("ripdpi.jacoco.enabled")
        .map { it.toBooleanStrict() }
        .orElse(
            providers.provider {
                gradle.startParameter.taskNames.any { taskName ->
                    taskName == "coverageReport" ||
                        taskName.endsWith(":coverageReport") ||
                        taskName.endsWith("jacocoDebugUnitTestReport") ||
                        taskName.endsWith("jacocoDebugUnitTestCoverageVerification") ||
                        taskName.endsWith("kotlinCoverageReport") ||
                        taskName.endsWith("kotlinCoverageVerification")
                }
            },
        )

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isEnabled = jacocoAgentEnabled.get()
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    group = "verification"
    description = "Generates JaCoCo coverage reports for debug unit tests."
    dependsOn(coverageUnitTestTaskName)

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(
        files(
            "src/main/java",
            "src/main/kotlin",
        ),
    )

    classDirectories.setFrom(coverageClassDirectories)
    executionData.setFrom(coverageExecutionData)
    onlyIf { executionData.files.any { it.exists() } }
}

tasks.register<JacocoCoverageVerification>("jacocoDebugUnitTestCoverageVerification") {
    group = "verification"
    description = "Verifies JaCoCo line coverage meets minimum thresholds for debug unit tests."
    dependsOn("jacocoDebugUnitTestReport")

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.05".toBigDecimal()
            }
        }
    }

    sourceDirectories.setFrom(
        files(
            "src/main/java",
            "src/main/kotlin",
        ),
    )

    classDirectories.setFrom(coverageClassDirectories)
    executionData.setFrom(coverageExecutionData)
}

tasks.named("jacocoDebugUnitTestReport") {
    finalizedBy("jacocoDebugUnitTestCoverageVerification")
}
