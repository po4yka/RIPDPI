import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
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

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

afterEvaluate {
    val debugUnitTest = tasks.findByName("testDebugUnitTest") ?: return@afterEvaluate
    val reportName = "jacocoDebugUnitTestReport"

    tasks.register<JacocoReport>(reportName) {
        group = "verification"
        description = "Generates JaCoCo coverage reports for debug unit tests."
        dependsOn(debugUnitTest)

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
}
