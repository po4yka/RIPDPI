import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.ByteArrayOutputStream
import javax.inject.Inject

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

@CacheableTask
abstract class CheckNoTrackedJavaSourcesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val javaSources = sourceFiles.files.sorted().map { it.relativeTo(project.rootDir).path }

        if (javaSources.isNotEmpty()) {
            throw GradleException(
                "Handwritten Java sources are not allowed:\n${javaSources.joinToString(separator = "\n")}",
            )
        }
    }
}

abstract class VerifyAppEngineBoundaryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appBuildFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val directEngineDependencyPattern = Regex("""project\(":core:engine"\)|projects\.core\.engine""")
        val appBuildText = appBuildFile.get().asFile.readText()
        if (directEngineDependencyPattern.containsMatchIn(appBuildText)) {
            throw GradleException(":app must not declare a direct dependency on :core:engine.")
        }

        val importViolations =
            sourceFiles.files
                .asSequence()
                .filter { it.isFile }
                .flatMap { sourceFile ->
                    sourceFile
                        .readLines()
                        .asSequence()
                        .mapIndexedNotNull { index, line ->
                            val trimmed = line.trim()
                            val isForbiddenImport =
                                trimmed.startsWith("import com.poyka.ripdpi.core.") &&
                                    !trimmed.startsWith("import com.poyka.ripdpi.core.detection.")
                            val isForbiddenQualifiedReference =
                                "com.poyka.ripdpi.core." in trimmed &&
                                    "com.poyka.ripdpi.core.detection." !in trimmed

                            if (isForbiddenImport || isForbiddenQualifiedReference) {
                                "${sourceFile.relativeTo(project.rootDir).path}:${index + 1}: $trimmed"
                            } else {
                                null
                            }
                        }
                }.toList()

        if (importViolations.isNotEmpty()) {
            throw GradleException(
                ":app production and unit-test sources must use service/diagnostics facades instead of " +
                    ":core:engine internals:\n${importViolations.joinToString(separator = "\n")}",
            )
        }
    }
}

val coverageModulePaths =
    listOf(
        ":app",
        ":core:data",
        ":core:diagnostics",
        ":core:engine",
        ":core:service",
    )

val qualityModulePaths = coverageModulePaths
val lintModulePaths = qualityModulePaths

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

fun handwrittenJavaSources() =
    fileTree(layout.projectDirectory) {
        include("app/src/**/*.java")
        include("baselineprofile/src/**/*.java")
        include("core/**/src/**/*.java")
        include("quality/**/src/**/*.java")
        include("build-logic/**/src/**/*.java")
        exclude("**/build/**")
        exclude("**/generated/**")
    }

tasks.register<JacocoReport>("kotlinCoverageReport") {
    group = "verification"
    description = "Aggregates Kotlin debug unit test JaCoCo reports across Android modules."
    dependsOn(coverageModulePaths.map { "$it:jacocoDebugUnitTestReport" })

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(
        coverageModulePaths.flatMap { modulePath ->
            listOf(
                layout.projectDirectory.dir(moduleRelativePath(modulePath)).dir("src/main/java"),
                layout.projectDirectory.dir(moduleRelativePath(modulePath)).dir("src/main/kotlin"),
            )
        },
    )
    classDirectories.setFrom(coverageModulePaths.map(::kotlinDebugCoverageClasses))
    executionData.setFrom(coverageModulePaths.map(::kotlinDebugCoverageExecutionData))
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

tasks.register<CheckNoTrackedJavaSourcesTask>("checkNoTrackedJavaSources") {
    group = "verification"
    description = "Fails if handwritten Java sources exist in repo-owned source sets."
    sourceFiles.from(handwrittenJavaSources())
}

tasks.register<VerifyAppEngineBoundaryTask>("verifyAppEngineBoundary") {
    group = "verification"
    description = "Ensures :app does not depend on :core:engine internals."
    appBuildFile.set(layout.projectDirectory.file("app/build.gradle.kts"))
    sourceFiles.from(
        fileTree(layout.projectDirectory.dir("app/src/main")) {
            include("**/*.kt")
            include("**/*.java")
        },
        fileTree(layout.projectDirectory.dir("app/src/test")) {
            include("**/*.kt")
            include("**/*.java")
        },
    )
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs detekt, ktlint, Android Lint, and file LoC checks across all modules"
    dependsOn(
        ":quality:detekt-rules:test",
        tasks.named("checkFileLocLimits"),
        tasks.named("checkNoTrackedJavaSources"),
        tasks.named("verifyAppEngineBoundary"),
        ":app:verifyEngineBoundaryClasspath",
        qualityModulePaths.map { "$it:detekt" },
        lintModulePaths.map { "$it:lintDebug" },
        qualityModulePaths.map { "$it:ktlintCheck" },
    )
}

tasks.register("installGitHooks") {
    group = "setup"
    description = "Installs lefthook git hooks for pre-commit checks"
    onlyIf { file(".git").exists() }
    doLast {
        val exitCode =
            ProcessBuilder("lefthook", "install", "--force")
                .directory(rootDir)
                .inheritIO()
                .start()
                .waitFor()
        if (exitCode != 0) {
            throw GradleException("lefthook install failed with exit code $exitCode")
        }
    }
}

tasks.matching { it.name == "prepareKotlinBuildScriptModel" }.configureEach {
    dependsOn("installGitHooks")
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
