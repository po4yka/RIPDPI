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
                        taskName.endsWith("kotlinCoverageReport")
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
    dependsOn("testDebugUnitTest")

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

    classDirectories.setFrom(
        files(
            fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
                exclude(jacocoExcludes)
            },
            fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
                exclude(jacocoExcludes)
            },
        ),
    )

    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
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
                minimum = "0.0".toBigDecimal()
            }
        }
    }

    sourceDirectories.setFrom(
        files(
            "src/main/java",
            "src/main/kotlin",
        ),
    )

    classDirectories.setFrom(
        files(
            fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
                exclude(jacocoExcludes)
            },
            fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
                exclude(jacocoExcludes)
            },
        ),
    )

    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("jacoco/testDebugUnitTest.exec")
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
}

tasks.named("jacocoDebugUnitTestReport") {
    finalizedBy("jacocoDebugUnitTestCoverageVerification")
}
