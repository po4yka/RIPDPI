import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
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
    alias(libs.plugins.compose.preview) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
    jacoco
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val robolectricSdkConfigurations =
    versionCatalog.versionAliases.filter { it.startsWith("robolectric.sdk.") }.map { alias ->
        // The sandboxes use different versions of the same Maven module.
        val version = versionCatalog.findVersion(alias).orElseThrow().requiredVersion
        val sdk = dependencies.create("org.robolectric:android-all-instrumented:$version")
        configurations.detachedConfiguration(sdk).apply { isTransitive = false }
    }
check(robolectricSdkConfigurations.isNotEmpty()) { "No Robolectric SDK versions in the version catalog" }
tasks.register<Sync>("prepareRobolectricSdks") {
    from(robolectricSdkConfigurations)
    into(layout.buildDirectory.dir("robolectric-sdks"))
}

// Generates docs/architecture/MODULE_GRAPH.md -- a Mermaid graph of inter-module
// dependencies. The plugin is applied from settings.gradle.kts. Regenerate with
// `./gradlew createModuleGraph` (or `just module-graph`), with no task after it on the
// command line: the plugin's task options cannot be parsed there. No CI job checks freshness.
moduleGraphConfig {
    readmePath = "docs/architecture/MODULE_GRAPH.md"
    heading = "## Module Graph"
    showFullPath = false
    setStyleByModuleType = true
    excludedConfigurationsRegex = ".*(test|androidTest|ksp|kapt).*"
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

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val rootDir = repoRoot.get().asFile
        val javaSources = sourceFiles.files.sorted().map { it.relativeTo(rootDir).path }

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

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val rootDir = repoRoot.get().asFile
        val directEngineDependencyPattern = Regex("""project\(":core:engine"\)|projects\.core\.engine""")
        val allowedDependencyConfigurations =
            setOf(
                "androidTestImplementation",
                "androidTestApi",
                "androidTestCompileOnly",
                "androidTestRuntimeOnly",
            )
        val directEngineDependencyViolations =
            appBuildFile
                .get()
                .asFile
                .readLines()
                .mapIndexedNotNull { index, line ->
                    val trimmed = line.trim()
                    val configuration = trimmed.substringBefore("(", missingDelimiterValue = "")
                    val isAllowedConfiguration = configuration in allowedDependencyConfigurations
                    if (directEngineDependencyPattern.containsMatchIn(trimmed) && !isAllowedConfiguration) {
                        "${appBuildFile.get().asFile.relativeTo(rootDir).path}:${index + 1}: $trimmed"
                    } else {
                        null
                    }
                }
        if (directEngineDependencyViolations.isNotEmpty()) {
            throw GradleException(
                ":app must not declare a direct production or unit-test dependency on :core:engine:\n" +
                    directEngineDependencyViolations.joinToString(separator = "\n"),
            )
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
                                "${sourceFile.relativeTo(rootDir).path}:${index + 1}: $trimmed"
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

val coreCoverageModulePaths =
    subprojects
        .map { it.path }
        .filter { modulePath -> modulePath.startsWith(":core:") }
        .sorted()
val coverageModulePaths = listOf(":app") + coreCoverageModulePaths

val qualityModulePaths =
    subprojects
        .map { it.path }
        .filter { modulePath -> modulePath == ":app" || modulePath.startsWith(":core:") }
        .sorted()
val lintTaskPaths =
    qualityModulePaths.flatMap { modulePath ->
        when (modulePath) {
            ":app" -> listOf(":app:lintGithubDebug")
            ":core:service" -> listOf(":core:service:lintStandardDebug", ":core:service:lintExtendedDebug")
            else -> listOf("$modulePath:lintDebug")
        }
    } + ":xray-protos:lint"

fun moduleRelativePath(modulePath: String): String = modulePath.removePrefix(":").replace(':', '/')

fun moduleBuildDir(modulePath: String) = layout.projectDirectory.dir(moduleRelativePath(modulePath)).dir("build")

fun coverageVariantName(modulePath: String): String =
    when (modulePath) {
        ":app" -> "githubDebug"
        ":core:service" -> "extendedDebug"
        else -> "debug"
    }

fun coverageVariantTaskSuffix(modulePath: String): String =
    coverageVariantName(modulePath).replaceFirstChar(Char::uppercaseChar)

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

fun kotlinDebugCoverageClasses(modulePath: String): FileCollection {
    val variantName = coverageVariantName(modulePath)
    val variantTaskSuffix = coverageVariantTaskSuffix(modulePath)
    val transformedClasses =
        moduleBuildDir(modulePath).dir(
            "intermediates/classes/$variantName/transform${variantTaskSuffix}ClassesWithAsm/dirs",
        )
    val kotlinClasses =
        moduleBuildDir(modulePath).dir(
            "intermediates/built_in_kotlinc/$variantName/compile${variantTaskSuffix}Kotlin/classes",
        )
    val javaClasses =
        moduleBuildDir(modulePath).dir(
            "intermediates/javac/$variantName/compile${variantTaskSuffix}JavaWithJavac/classes",
        )
    return files(
        provider {
            if (transformedClasses.asFile.isDirectory) {
                listOf(transformedClasses.asFile)
            } else {
                listOf(kotlinClasses.asFile, javaClasses.asFile)
            }
        },
    ).asFileTree.matching {
        exclude(kotlinCoverageExcludes)
    }
}

fun kotlinDebugCoverageExecutionData(modulePath: String): FileTree {
    val variantName = coverageVariantName(modulePath)
    val testTaskName = "test${coverageVariantTaskSuffix(modulePath)}UnitTest"
    return fileTree(moduleBuildDir(modulePath)) {
        include("jacoco/$testTaskName.exec")
        include("outputs/unit_test_code_coverage/${variantName}UnitTest/$testTaskName.exec")
    }
}

fun debugCoverageSources(modulePath: String) =
    buildList {
        val moduleDir = layout.projectDirectory.dir(moduleRelativePath(modulePath))
        add(moduleDir.dir("src/main/java"))
        add(moduleDir.dir("src/main/kotlin"))
        if (modulePath == ":app" || modulePath == ":core:service") {
            add(moduleDir.dir("src/extended/kotlin"))
        }
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

    sourceDirectories.setFrom(coverageModulePaths.flatMap(::debugCoverageSources))
    classDirectories.setFrom(coverageModulePaths.map(::kotlinDebugCoverageClasses))
    executionData.setFrom(coverageModulePaths.map(::kotlinDebugCoverageExecutionData))
    onlyIf { executionData.files.any { it.exists() } }
}

tasks.register<JacocoCoverageVerification>("kotlinCoverageVerification") {
    group = "verification"
    description = "Verifies aggregate Kotlin line coverage across Android modules."
    dependsOn("kotlinCoverageReport")

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.40".toBigDecimal()
            }
        }
    }

    sourceDirectories.setFrom(coverageModulePaths.flatMap(::debugCoverageSources))
    classDirectories.setFrom(coverageModulePaths.map(::kotlinDebugCoverageClasses))
    executionData.setFrom(coverageModulePaths.map(::kotlinDebugCoverageExecutionData))
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Runs aggregate Kotlin coverage reporting and verification."
    dependsOn("kotlinCoverageVerification")
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
    repoRoot.set(layout.projectDirectory)
    sourceFiles.from(handwrittenJavaSources())
}

tasks.register<VerifyAppEngineBoundaryTask>("verifyAppEngineBoundary") {
    group = "verification"
    description = "Ensures :app does not depend on :core:engine internals."
    repoRoot.set(layout.projectDirectory)
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

tasks.register<Exec>("checkAsnExposureDenylistAssets") {
    group = "verification"
    description = "Verifies ASN exposure denylist data is not bundled in Android runtime assets."
    inputs.file(layout.projectDirectory.file("scripts/ci/check_asn_exposure_denylist_assets.py"))
    inputs.files(
        fileTree(layout.projectDirectory) {
            include("**/src/main/assets/**")
            exclude("**/build/**")
        },
    )
    workingDir = rootDir
    commandLine("python3", "scripts/ci/check_asn_exposure_denylist_assets.py")
}

tasks.register<Exec>("checkShippedAssetSecrets") {
    group = "verification"
    description = "Fails when a shipped asset carries credential material or cannot be scanned."
    inputs.files(
        layout.projectDirectory.file("scripts/ci/check_shipped_asset_secrets.py"),
        layout.projectDirectory.file("scripts/ci/_common.py"),
    )
    inputs.files(
        fileTree(layout.projectDirectory) {
            include("**/src/*/assets/**")
            // The script skips nested checkouts, tool state, and build outputs; they are not inputs either.
            exclude("**/build/**", "**/target/**", ".*/**", "**/.*/**")
        },
    )
    val stamp = layout.buildDirectory.file("checkShippedAssetSecrets/passed.stamp")
    outputs.file(stamp)
    workingDir = rootDir
    commandLine("python3", "scripts/ci/check_shipped_asset_secrets.py")
    doLast { stamp.get().asFile.writeText("passed\n") }
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs detekt, ktlint, Android Lint, and file LoC checks across all modules"
    dependsOn(
        ":quality:detekt-rules:test",
        tasks.named("checkFileLocLimits"),
        tasks.named("checkAsnExposureDenylistAssets"),
        tasks.named("checkShippedAssetSecrets"),
        tasks.named("checkNoTrackedJavaSources"),
        tasks.named("verifyAppEngineBoundary"),
        ":app:verifyEngineBoundaryClasspath",
        qualityModulePaths.map { "$it:detekt" },
        lintTaskPaths,
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
    dependsOn(":app:recordRoborazziGithubDebug")
}

tasks.register("verifyScreenshots") {
    group = "verification"
    description = "Verifies Roborazzi screenshot baselines for the app module"
    dependsOn(":app:verifyRoborazziGithubDebug")
}
