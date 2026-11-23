import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.FinalizeBundleTask
import com.android.build.gradle.internal.tasks.PackageBundleTask
import com.android.build.gradle.tasks.PackageAndroidArtifact
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject

abstract class VerifyEngineBoundaryClasspathTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val forbiddenArtifacts: ConfigurableFileCollection

    @get:Input
    abstract val forbiddenProjectPath: Property<String>

    @TaskAction
    fun verify() {
        if (!forbiddenArtifacts.isEmpty) {
            throw GradleException(
                "${forbiddenProjectPath.get()} must not appear on :app compile classpaths.",
            )
        }
    }
}

abstract class VerifyReleaseVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val refName: Property<String>

    @TaskAction
    fun verify() {
        val currentRef = refName.get()
        if (!currentRef.startsWith("v")) {
            logger.lifecycle("Skipping release tag/version check for ref '$currentRef'")
            return
        }
        val expectedVersion = currentRef.removePrefix("v")
        if (expectedVersion != versionName.get()) {
            throw GradleException(
                "Release tag $currentRef does not match app versionName ${versionName.get()} " +
                    "(versionCode ${versionCode.get()}).",
            )
        }
    }
}

object RustNotices {
    const val FILE = "rust-crates.json"
}

// Writes notices/rust-crates.json in the same shape as the libXray notices/go-modules.json:
// deduplicated license texts and the packages that use them. CI jobs that build the app from
// prebuilt jniLibs pass the file generated next to those libraries instead of running cargo-about.
abstract class GenerateRustNoticesTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val cargoLock: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val aboutConfig: RegularFileProperty

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val cargoManifests: ConfigurableFileCollection

        // Absent when cargo-about is not installed; installing or upgrading it reruns the task.
        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val cargoAbout: RegularFileProperty

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val prebuiltNotices: RegularFileProperty

        @get:Input
        abstract val cargoAboutVersion: Property<String>

        @get:Input
        abstract val required: Property<Boolean>

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        init {
            // A build without cargo-about must not be reused once it is installed.
            outputs.upToDateWhen { cargoAbout.isPresent || prebuiltNotices.isPresent }
        }

        @TaskAction
        fun generate() {
            val noticesDir = outputDir.get().asFile.resolve("notices")
            noticesDir.deleteRecursively()
            noticesDir.mkdirs()
            val target = noticesDir.resolve(RustNotices.FILE)
            if (prebuiltNotices.isPresent) {
                prebuiltNotices.get().asFile.copyTo(target)
                return
            }
            val version = cargoAboutVersion.get()
            val install =
                "cargo-about $version (cargo install cargo-about@$version --locked --features cli), " +
                    "or -Pripdpi.prebuiltRustNoticesDir with prebuilt jniLibs"
            if (!cargoAbout.isPresent) {
                val message = "Rust third-party notices need $install."
                if (required.get()) throw GradleException(message)
                logger.warn("$message The open-source licenses screen omits Rust crates in this build.")
                return
            }
            val executable = cargoAbout.get().asFile.absolutePath
            val versionOutput = ByteArrayOutputStream()
            execOperations.exec {
                commandLine(executable, "--version")
                standardOutput = versionOutput
            }
            val installed = versionOutput.toString().trim()
            if (installed.substringAfterLast(' ') != version) {
                throw GradleException("$executable reports '$installed'; Rust third-party notices need $install.")
            }
            val rawJson = temporaryDir.resolve("cargo-about.json")
            val config = aboutConfig.get().asFile
            execOperations.exec {
                workingDir = config.parentFile
                commandLine(
                    executable,
                    "generate",
                    "--locked",
                    "--format",
                    "json",
                    "-c",
                    config.absolutePath,
                    "-o",
                    rawJson.absolutePath,
                )
            }
            target.writeText(compact(rawJson.readText()))
        }

        // The workspace's own AGPL crates are covered by the app license in About.
        @Suppress("UNCHECKED_CAST")
        private fun compact(rawJson: String): String {
            val model = JsonSlurper().parseText(rawJson) as Map<String, Any?>
            val licenses =
                (model.getValue("licenses") as List<Map<String, Any?>>).filter { it["id"] != "AGPL-3.0-only" }
            val crates = sortedMapOf<String, Map<String, Any?>>()
            val crateLicenses = mutableMapOf<String, MutableList<Int>>()
            licenses.forEachIndexed { index, license ->
                (license.getValue("used_by") as List<Map<String, Any?>>).forEach { use ->
                    val crate = use.getValue("crate") as Map<String, Any?>
                    val key = "${crate["name"]}@${crate["version"]}"
                    crates[key] = crate
                    crateLicenses.getOrPut(key) { mutableListOf() } += index
                }
            }
            return JsonOutput.toJson(
                mapOf(
                    "licenses" to licenses.map { mapOf("id" to it["id"], "text" to it["text"]) },
                    "packages" to
                        crates.map { (key, crate) ->
                            mapOf(
                                "name" to crate["name"],
                                "version" to crate["version"],
                                "license" to (crate["license"] ?: ""),
                                "note" to noticeText(crate.getValue("manifest_path") as String),
                                "licenses" to crateLicenses.getValue(key),
                            )
                        },
                ),
            )
        }

        // Apache-2.0 section 4(d): redistributions carry the upstream NOTICE text.
        private fun noticeText(manifestPath: String): String =
            File(manifestPath)
                .parentFile
                .listFiles { file -> file.isFile && file.name.uppercase().startsWith("NOTICE") }
                .orEmpty()
                .sortedBy(File::getName)
                .joinToString("\n\n") { it.readText().trim() }
    }

// Gives each variant its own copy of the shared notices, as AGP's generated-asset API requires.
abstract class CopyRustNoticesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notices: ConfigurableFileCollection

    // Release variants ship native code and must carry its notices, also in local builds.
    @get:Input
    abstract val required: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        notices.files.filter(File::isDirectory).forEach { it.copyRecursively(target) }
        if (required.get() && !target.resolve("notices/${RustNotices.FILE}").isFile) {
            throw GradleException("$name: release builds need notices/${RustNotices.FILE}; install cargo-about.")
        }
    }
}

abstract class StageExtendedRuntimeAssetsTask
    @Inject
    constructor(
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val assets: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun stage() {
            fileSystemOperations.sync {
                from(assets)
                into(outputDir)
            }
        }
    }

abstract class WriteReleaseIdentityManifestTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val applicationIds: MapProperty<String, String>

    @get:Input
    abstract val distributions: MapProperty<String, String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeManifest() {
        val distributionByVariant = distributions.get()
        val variants =
            applicationIds
                .get()
                .toSortedMap()
                .map { (variantName, applicationId) ->
                    mapOf(
                        "name" to variantName,
                        "distribution" to distributionByVariant.getValue(variantName),
                        "applicationId" to applicationId,
                    )
                }
        val manifest =
            mapOf(
                "schemaVersion" to 1,
                "versionName" to versionName.get(),
                "versionCode" to versionCode.get(),
                "variants" to variants,
            )
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
        }
    }
}

plugins {
    id("ripdpi.android.application")
    id("ripdpi.android.coverage")
    id("ripdpi.android.compose")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.roborazzi")
    id("ripdpi.android.serialization")
    alias(libs.plugins.aboutlibraries.android)
}

// One APK bundle supplies every emulator in the CI API matrix.
val stageCiInstrumentationApks =
    tasks.register<Sync>("stageCiInstrumentationApks") {
        group = "verification"
        into(layout.buildDirectory.dir("outputs/ci-instrumentation"))
        from(configurations.named("androidTestUtil")) {
            into("utilities")
        }
    }

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (variant.name == "githubDebug") {
            val androidTest = variant.androidTest ?: return@onVariants
            stageCiInstrumentationApks.configure {
                from(variant.artifacts.get(SingleArtifact.APK)) {
                    into("${variant.name}/app")
                }
                from(androidTest.artifacts.get(SingleArtifact.APK)) {
                    into("${variant.name}/test")
                }
            }
        }
    }
}

val includeRoborazziUnitTests =
    providers
        .gradleProperty("ripdpi.includeRoborazziUnitTests")
        .map { it.toBooleanStrict() }
        .orElse(
            providers.provider {
                gradle.startParameter.taskNames.any { taskName ->
                    taskName.contains("Roborazzi", ignoreCase = true)
                }
            },
        )

tasks.withType<Test>().configureEach {
    if (!includeRoborazziUnitTests.get()) {
        exclude("**/ui/screenshot/**")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    if (name.endsWith("AndroidTestKotlin")) {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions",
            )
        }
    }
}

val localProps =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use(::load)
    }

fun localOrEnv(
    propKey: String,
    envKey: String,
): String? = localProps.getProperty(propKey) ?: providers.environmentVariable(envKey).orNull

val releaseStoreFilePath = localOrEnv("signing.storeFile", "RIPDPI_SIGNING_STORE_FILE")
val releaseStorePassword = localOrEnv("signing.storePassword", "RIPDPI_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = localOrEnv("signing.keyAlias", "RIPDPI_SIGNING_KEY_ALIAS")
val releaseKeyPassword = localOrEnv("signing.keyPassword", "RIPDPI_SIGNING_KEY_PASSWORD")
val ripdpiVersionCode = 14
val ripdpiVersionName = "0.2.1"

val forwardedInstrumentationArguments =
    listOf(
        "class",
        "notClass",
        "package",
        "notPackage",
        "coverage",
        "ripdpi.fixtureControlHost",
        "ripdpi.fixtureControlPort",
        "ripdpi.xrayFixturePort",
        "ripdpi.xrayFixtureHost",
        "ripdpi.xrayControlHost",
        "ripdpi.acceptanceRunId",
        "ripdpi.packetSmokeDeviceProfile",
        "ripdpi.runNetworkTests",
    )

val extendedDistributions = setOf("fdroid", "github")

extensions.configure<ApplicationExtension> {
    packaging.resources {
        // Merge rules take priority over exclusions. Name the Android services
        // so dnsjava's desktop resolver SPI descriptors can be excluded.
        merges -= "/META-INF/services/**"
        merges +=
            setOf(
                "/META-INF/services/io.grpc.LoadBalancerProvider",
                "/META-INF/services/io.grpc.ManagedChannelProvider",
                "/META-INF/services/io.grpc.NameResolverProvider",
                "/META-INF/services/io.grpc.ServerProvider",
                "/META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler",
                "/META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory",
            )
        excludes +=
            setOf(
                "META-INF/services/java.net.spi.InetAddressResolverProvider",
                "META-INF/services/sun.net.spi.nameservice.NameServiceDescriptor",
            )
    }
    sourceSets
        .getByName("test")
        .kotlin.directories
        .add("src/testShared/kotlin")
    sourceSets
        .getByName("androidTest")
        .kotlin.directories
        .add("src/testShared/kotlin")

    sourceSets.getByName("androidTest").assets.srcDir(
        rootProject.file("native/rust/crates/ripdpi-packets/tests/fixtures/quic"),
    )

    namespace = "com.poyka.ripdpi"
    testBuildType = providers.gradleProperty("ripdpi.testBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = "com.poyka.ripdpi"
        versionCode = ripdpiVersionCode
        versionName = ripdpiVersionName

        testInstrumentationRunner = "com.poyka.ripdpi.HiltTestRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        forwardedInstrumentationArguments.forEach { argumentName ->
            providers
                .gradleProperty("android.testInstrumentationRunnerArguments.$argumentName")
                .orNull
                ?.let { argumentValue ->
                    testInstrumentationRunnerArguments[argumentName] = argumentValue
                }
        }
    }

    signingConfigs {
        releaseStoreFilePath?.let { configuredStoreFile ->
            create("release") {
                storeFile = file(configuredStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            missingDimensionStrategy("runtimeEdition", "standard")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"play\"")
            buildConfigField("String", "GITHUB_UPDATE_OWNER", "\"\"")
            buildConfigField("String", "GITHUB_UPDATE_REPO", "\"\"")
        }
        create("fdroid") {
            dimension = "distribution"
            missingDimensionStrategy("runtimeEdition", "extended")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"fdroid\"")
            buildConfigField("String", "GITHUB_UPDATE_OWNER", "\"\"")
            buildConfigField("String", "GITHUB_UPDATE_REPO", "\"\"")
        }
        create("github") {
            dimension = "distribution"
            missingDimensionStrategy("runtimeEdition", "extended")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"github\"")
            buildConfigField("String", "GITHUB_UPDATE_OWNER", "\"po4yka\"")
            buildConfigField("String", "GITHUB_UPDATE_REPO", "\"RIPDPI\"")
        }
    }

    extendedDistributions.forEach { distribution ->
        sourceSets.getByName(distribution).apply {
            kotlin.directories.add("src/extended/kotlin")
            res.directories.add("src/extended/res")
        }
        val flavorSuffix = distribution.replaceFirstChar(Char::uppercaseChar)
        sourceSets
            .maybeCreate("androidTest$flavorSuffix")
            .kotlin.directories
            .add("src/androidTestExtended/kotlin")
        sourceSets
            .maybeCreate("test$flavorSuffix")
            .kotlin.directories
            .add("src/testExtended/kotlin")
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
        unitTests.all { task ->
            // Roborazzi captures bitmaps; large screen catalogs (logsScreen,
            // homeCompactScreen, etc.) accumulate heap under the default
            // 512MB test JVM and OOM mid-run. 3GB is enough for the full
            // screenshot catalog and keeps the parallel forks within host memory.
            task.maxHeapSize = "3g"
        }
    }

    val gitCommit: String =
        providers
            .exec { commandLine("git", "rev-parse", "--short", "HEAD") }
            .standardOutput
            .asText
            .map { it.trim() }
            .orElse("unavailable")
            .get()

    val nativeLibVersion: String =
        providers
            .provider {
                rootProject.file("native/rust/Cargo.toml").useLines { lines ->
                    lines
                        .firstOrNull { it.trimStart().startsWith("version") && it.contains("\"") }
                        ?.substringAfter('"')
                        ?.substringBefore('"')
                        ?: "unavailable"
                }
            }.get()

    val diagnosticShareBaseUrl =
        providers
            .gradleProperty("ripdpi.diagnosticShareBaseUrl")
            .orElse("https://po4yka.github.io/RIPDPI/share")
            .get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            testProguardFiles("proguard-androidtest-rules.pro")
            if (testBuildType == "release") {
                proguardFiles("proguard-androidtest-target-rules.pro")
            }
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "NATIVE_LIB_VERSION", "\"$nativeLibVersion\"")
            buildConfigField("String", "DIAGNOSTIC_SHARE_BASE_URL", "\"$diagnosticShareBaseUrl\"")
        }
        debug {
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}-debug\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "NATIVE_LIB_VERSION", "\"$nativeLibVersion\"")
            buildConfigField("String", "DIAGNOSTIC_SHARE_BASE_URL", "\"$diagnosticShareBaseUrl\"")
            enableAndroidTestCoverage = true
        }
        create("benchmark") {
            initWith(getByName("release"))
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}-bench\"")
            buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
            buildConfigField("String", "NATIVE_LIB_VERSION", "\"$nativeLibVersion\"")
            buildConfigField("String", "DIAGNOSTIC_SHARE_BASE_URL", "\"$diagnosticShareBaseUrl\"")
        }
    }

    // https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    // dnsjava retains this CLASS-retention annotation; it is not a runtime dependency.
    compileOnly(libs.lombok)
    implementation(libs.aboutlibraries.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)

    implementation(libs.bundles.lifecycle.app)
    implementation(libs.androidx.lifecycle.process)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.bundles.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.runtime.tracing)
    "benchmarkImplementation"(libs.androidx.compose.runtime.tracing)
    // Load tracing from the installed APK; Android 17 rejects a writable sideloaded library.
    "benchmarkImplementation"(libs.androidx.tracing.perfetto.binary)
    debugImplementation(libs.androidx.test.core.ktx)
    // Debug-only heap-leak detection (auto-installs via its own ContentProvider;
    // never present in release/benchmark builds). See the Android 17 memory
    // guidance: retained Activity/Fragment/View refs raise the per-app cap risk.
    debugImplementation(libs.leakcanary.android)

    // Modules
    implementation(project(":core:data"))
    implementation(project(":core:detection"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:pcap-export"))
    implementation(project(":core:service"))
    // SshHostKeyObserver returns the engine-api probe result types to the SSH editor.
    implementation(project(":core:engine-api"))

    // Proto DataStore
    implementation(libs.androidx.datastore)

    implementation(libs.kermit)
    implementation(libs.process.phoenix)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Glance app widgets
    implementation(libs.bundles.glance)
    implementation(libs.androidx.datastore.preferences)

    // QR scanner: CameraX preview/analysis + ML Kit on-device barcode scanning.
    implementation(libs.bundles.camerax)
    implementation(libs.mlkit.barcode.scanning)
    // QR generation: zxing-core only (no Play Services), offline encoding for profile share.
    implementation(libs.zxing.core)
    testImplementation(testFixtures(project(":core:data")))
    testImplementation(testFixtures(project(":core:data:settings")))
    testImplementation(testFixtures(project(":core:data:runtime-state")))

    testImplementation(libs.bundles.unit.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(project(":core:diagnostics-data"))
    testImplementation(testFixtures(project(":core:service")))
    androidTestImplementation(testFixtures(project(":core:service")))
    testImplementation(project(":core:engine-api"))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // Material breaks androidTest resource linking (attr/drawerLayoutStyle) and the app is Compose-only;
    // protobuf-lite 3.0.1 (via espresso-contrib's accessibility framework) shadows the app's protobuf-javalite.
    androidTestImplementation(libs.kaspresso) {
        exclude(group = "com.google.android.material")
        exclude(group = "com.google.protobuf", module = "protobuf-lite")
    }
    androidTestImplementation(libs.kaspresso.compose.support) {
        exclude(group = "com.google.android.material")
        exclude(group = "com.google.protobuf", module = "protobuf-lite")
    }
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(project(":core:diagnostics-data"))
    androidTestImplementation(project(":core:engine"))
    androidTestImplementation(project(":testing:socket-bind-probe"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register<VerifyEngineBoundaryClasspathTask>("verifyEngineBoundaryClasspath") {
    group = "verification"
    description = "Fails if :core:engine leaks onto :app compile classpaths."
    forbiddenProjectPath.set(":core:engine")

    configurations
        .matching { configuration ->
            configuration.name.endsWith("CompileClasspath") &&
                !configuration.name.startsWith("androidTest") &&
                !configuration.name.startsWith("test") &&
                !configuration.name.contains("AndroidTest") &&
                !configuration.name.contains("UnitTest")
        }.forEach { configuration ->
            forbiddenArtifacts.from(
                configuration.incoming
                    .artifactView {
                        componentFilter { componentId ->
                            val projectId = componentId as? ProjectComponentIdentifier
                            projectId?.projectPath == ":core:engine"
                        }
                    }.files,
            )
        }
}

plugins.withId("com.android.application") {
    tasks.register<PreparePresetTask>("preparePreset") {
        group = "build setup"
        description = "Writes src/main/assets/preset.json from a FULL backup export (-Pripdpi.presetArchive=<file>)."
        archive.set(layout.file(providers.gradleProperty("ripdpi.presetArchive").map(rootProject::file)))
        schemaSource.set(
            rootProject.file("core/data/src/main/kotlin/com/poyka/ripdpi/data/backup/BackupV1.kt"),
        )
        presetFile.set(layout.projectDirectory.file("src/main/assets/preset.json"))
    }

    tasks.register<VerifyReleaseVersionTask>("verifyReleaseVersion") {
        group = "verification"
        description = "Fails release builds when refs/tags/v* does not match :app versionName."
        versionName.set(ripdpiVersionName)
        versionCode.set(ripdpiVersionCode)
        refName.set(
            providers
                .environmentVariable("GITHUB_REF_NAME")
                .orElse(providers.gradleProperty("ripdpi.releaseRefName"))
                .orElse(""),
        )
    }

    val writeReleaseIdentityManifest =
        tasks.register<WriteReleaseIdentityManifestTask>("writeReleaseIdentityManifest") {
            group = "verification"
            description = "Writes resolved release variant application IDs for the identity review gate."
            versionName.set(ripdpiVersionName)
            versionCode.set(ripdpiVersionCode)
            outputFile.set(layout.buildDirectory.file("reports/app-identity/release-identity.json"))
        }

    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants(selector().withBuildType("release")) { variant ->
            val flavors = variant.productFlavors.toMap()
            writeReleaseIdentityManifest.configure {
                applicationIds.put(variant.name, variant.applicationId)
                distributions.put(variant.name, flavors.getValue("distribution"))
            }
        }
    }
}

// Guard the actual packaging tasks, including calls through aggregate/custom tasks.
// Unit tests and lint remain native-less; no APK or bundle may contain the throwing stub.
tasks.withType<PackageAndroidArtifact>().configureEach {
    dependsOn(":checkShippedAssetSecrets")
    dependsOn(
        if (name.contains("Release", ignoreCase = true)) {
            ":core:engine:verifyLibXrayReleaseArtifacts"
        } else {
            ":core:engine:verifyLibXrayArtifacts"
        },
    )
}
tasks.withType<PackageBundleTask>().configureEach {
    dependsOn(":checkShippedAssetSecrets", ":core:engine:verifyLibXrayReleaseArtifacts")
}
tasks.withType<FinalizeBundleTask>().configureEach {
    dependsOn(":core:engine:verifyLibXrayReleaseArtifacts")
}

aboutLibraries {
    collect {
        // Components that Gradle metadata cannot describe (Go standard library, ripdpi-socks5-core).
        configPath = file("config/aboutlibraries")
    }
}

// A native-less validation build ships no Rust code, so it needs no Rust notices.
val nativeLessValidation = providers.gradleProperty("ripdpi.skipNativeBuild").orNull == "true"

val extendedRuntimeAssets =
    configurations.create("extendedRuntimeAssets") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    add(
        extendedRuntimeAssets.name,
        project(path = ":core:engine", configuration = "extendedRuntimeAssetsElements"),
    )
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().all()) { variant ->
        val distribution = variant.productFlavors.toMap().getValue("distribution")
        if (!nativeLessValidation && distribution in extendedDistributions) {
            val stageAssets =
                tasks.register<StageExtendedRuntimeAssetsTask>(
                    variant.computeTaskName("stage", "ExtendedRuntimeAssets"),
                ) {
                    assets.from(extendedRuntimeAssets)
                }
            variant.sources.assets?.addGeneratedSourceDirectory(stageAssets, StageExtendedRuntimeAssetsTask::outputDir)
        }
    }
}

val generateRustNotices =
    tasks.register<GenerateRustNoticesTask>("generateRustNotices") {
        group = "build"
        description = "Generates the Rust third-party notices asset with cargo-about."
        val rustWorkspace = rootProject.layout.projectDirectory.dir("native/rust")
        cargoLock.set(rustWorkspace.file("Cargo.lock"))
        aboutConfig.set(rustWorkspace.file("about.toml"))
        cargoManifests.from(
            fileTree(rustWorkspace) {
                include("**/Cargo.toml")
                exclude("**/target/**")
            },
        )
        val cargoHome =
            providers
                .environmentVariable("CARGO_HOME")
                .orElse(providers.systemProperty("user.home").map { "$it/.cargo" })
        val installedCargoAbout =
            providers
                .gradleProperty("ripdpi.cargoAbout")
                .orElse(cargoHome.map { "$it/bin/cargo-about" })
                .map(::File)
                .filter(File::canExecute)
        cargoAbout.fileProvider(installedCargoAbout)
        cargoAboutVersion.set(libs.versions.cargo.about)
        prebuiltNotices.fileProvider(
            providers
                .gradleProperty("ripdpi.prebuiltRustNoticesDir")
                .filter(String::isNotBlank)
                .map { File(it, RustNotices.FILE) },
        )
        // CI builds that package native code must ship the notices; a local debug build without
        // cargo-about only warns. Release variants enforce the file in their copy task.
        val ciRequiresNotices = !nativeLessValidation
        required.set(
            providers
                .gradleProperty("ripdpi.requireRustNotices")
                .map(String::toBoolean)
                .orElse(providers.environmentVariable("CI").map { ciRequiresNotices })
                .orElse(false),
        )
        outputDir.set(layout.buildDirectory.dir("generated/rustNotices"))
    }

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().all()) { variant ->
        val copyRustNotices =
            tasks.register<CopyRustNoticesTask>(variant.computeTaskName("copy", "RustNotices")) {
                notices.from(generateRustNotices.flatMap(GenerateRustNoticesTask::outputDir))
                required.set(variant.buildType == "release" && !nativeLessValidation)
            }
        variant.sources.assets?.addGeneratedSourceDirectory(copyRustNotices, CopyRustNoticesTask::outputDir)
    }
}
