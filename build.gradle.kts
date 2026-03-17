import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Internal
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.process.ExecOperations

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.roborazzi) apply false
    jacoco
}

@CacheableTask
abstract class CheckFileLocLimitsTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val scriptFile: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val baselineFile: RegularFileProperty

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sourceFiles: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val reportDir: DirectoryProperty

        @get:Internal
        abstract val repoRoot: DirectoryProperty

        @TaskAction
        fun verify() {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val result =
                execOperations.exec {
                    workingDir = repoRoot.get().asFile
                    isIgnoreExitValue = true
                    standardOutput = stdout
                    errorOutput = stderr
                    commandLine(
                        "python3",
                        scriptFile.get().asFile.absolutePath,
                        "--baseline",
                        baselineFile.get().asFile.absolutePath,
                        "--report-dir",
                        reportDir.get().asFile.absolutePath,
                    )
                }

            if (result.exitValue != 0) {
                val errorOutput = stderr.toString().ifBlank { stdout.toString() }.trim()
                throw GradleException(
                    errorOutput.ifBlank { "File LoC limits verification failed with exit code ${result.exitValue}." },
                )
            }
        }
    }

val androidModulePaths =
    listOf(
        ":app",
        ":core:data",
        ":core:diagnostics",
        ":core:diagnostics-data",
        ":core:engine",
        ":core:service",
    )

val lintModulePaths = androidModulePaths

fun moduleRelativePath(modulePath: String): String = modulePath.removePrefix(":").replace(':', '/')

fun moduleBuildDir(modulePath: String) = layout.projectDirectory.dir(moduleRelativePath(modulePath)).dir("build")

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

fun kotlinDebugCoverageClasses(modulePath: String) =
    files(
        fileTree(moduleBuildDir(modulePath).dir("tmp/kotlin-classes/debug")) {
            exclude(kotlinCoverageExcludes)
        },
        fileTree(moduleBuildDir(modulePath).dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            exclude(kotlinCoverageExcludes)
        },
    )

fun kotlinDebugCoverageExecutionData(modulePath: String) =
    fileTree(moduleBuildDir(modulePath)) {
        include("jacoco/testDebugUnitTest.exec")
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    }

tasks.register<JacocoReport>("kotlinCoverageReport") {
    group = "verification"
    description = "Aggregates Kotlin debug unit test JaCoCo reports across Android modules."
    dependsOn(androidModulePaths.map { "$it:jacocoDebugUnitTestReport" })

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(
        androidModulePaths.flatMap { modulePath ->
            listOf(
                layout.projectDirectory.dir(moduleRelativePath(modulePath)).dir("src/main/java"),
                layout.projectDirectory.dir(moduleRelativePath(modulePath)).dir("src/main/kotlin"),
            )
        },
    )
    classDirectories.setFrom(androidModulePaths.map(::kotlinDebugCoverageClasses))
    executionData.setFrom(androidModulePaths.map(::kotlinDebugCoverageExecutionData))
    onlyIf { executionData.files.any { it.exists() } }
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Runs aggregate Kotlin coverage reporting."
    dependsOn("kotlinCoverageReport")
}

tasks.register<CheckFileLocLimitsTask>("checkFileLocLimits") {
    group = "verification"
    description = "Verifies code-only LoC limits for repo-owned Rust and Kotlin source files."
    scriptFile.set(layout.projectDirectory.file("scripts/ci/check_file_loc_limits.py"))
    baselineFile.set(layout.projectDirectory.file("config/static/file-loc-baseline.json"))
    reportDir.set(layout.buildDirectory.dir("reports/file-loc-limits"))
    repoRoot.set(layout.projectDirectory)
    sourceFiles.from(
        fileTree(layout.projectDirectory) {
            include("app/src/main/**/*.kt")
            include("core/**/src/main/**/*.kt")
            include("native/rust/crates/**/*.rs")
            exclude("**/build/**")
            exclude("**/generated/**")
        },
    )
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs detekt, ktlint, Android Lint, and file LoC checks across all modules"
    dependsOn(
        ":quality:detekt-rules:test",
        tasks.named("checkFileLocLimits"),
        androidModulePaths.map { "$it:detekt" },
        lintModulePaths.map { "$it:lintDebug" },
        androidModulePaths.map { "$it:ktlintCheck" },
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
