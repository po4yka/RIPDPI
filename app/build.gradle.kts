import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.FinalizeBundleTask
import com.android.build.gradle.internal.tasks.PackageBundleTask
import com.android.build.gradle.tasks.PackageAndroidArtifact
import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

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

abstract class VerifyEmbeddedRelayBundleTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val bundleFile = bundleFiles.singleFile
        if (!bundleFile.isFile || bundleFile.length() == 0L) {
            throw GradleException(
                "Simple variants require a non-empty src/simple/assets/embedded-relay-bundle.json",
            )
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

    @get:Input
    abstract val experiences: MapProperty<String, String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeManifest() {
        val distributionByVariant = distributions.get()
        val experienceByVariant = experiences.get()
        val variants =
            applicationIds
                .get()
                .toSortedMap()
                .map { (variantName, applicationId) ->
                    mapOf(
                        "name" to variantName,
                        "distribution" to distributionByVariant.getValue(variantName),
                        "experience" to experienceByVariant.getValue(variantName),
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
val ripdpiVersionCode = 12
val ripdpiVersionName = "0.1.4"

val forwardedInstrumentationArguments =
    listOf(
        "class",
        "notClass",
        "package",
        "notPackage",
        "coverage",
        "ripdpi.fixtureControlHost",
        "ripdpi.fixtureControlPort",
        "ripdpi.packetSmokeDeviceProfile",
        "ripdpi.runNetworkTests",
    )

extensions.configure<ApplicationExtension> {
    namespace = "com.poyka.ripdpi"
    testBuildType = providers.gradleProperty("ripdpi.testBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = "com.poyka.ripdpi"
        versionCode = ripdpiVersionCode
        versionName = ripdpiVersionName

        testInstrumentationRunner = "com.poyka.ripdpi.HiltTestRunner"
        testInstrumentationRunnerArguments["clearPackageData"] =
            providers.gradleProperty("ripdpi.androidTestClearPackageData").orElse("true").get()
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

    // Two orthogonal dimensions: "distribution" is the store channel; "experience"
    // is the UI surface. "full" is the standard app; "simple" is a stripped-down
    // two-action build (connect + diagnostic report) with a compiled-in config.
    // distribution is declared first so variants read e.g. githubSimpleDebug.
    flavorDimensions += listOf("distribution", "experience")
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"play\"")
            buildConfigField("String", "GITHUB_UPDATE_OWNER", "\"\"")
            buildConfigField("String", "GITHUB_UPDATE_REPO", "\"\"")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"fdroid\"")
            buildConfigField("String", "GITHUB_UPDATE_OWNER", "\"\"")
            buildConfigField("String", "GITHUB_UPDATE_REPO", "\"\"")
        }
        create("github") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"github\"")
            buildConfigField("String", "GITHUB_UPDATE_OWNER", "\"po4yka\"")
            buildConfigField("String", "GITHUB_UPDATE_REPO", "\"RIPDPI\"")
        }
        create("full") {
            dimension = "experience"
            buildConfigField("String", "APP_EXPERIENCE", "\"full\"")
        }
        create("simple") {
            dimension = "experience"
            // Side-by-side install with the standard build for field testing.
            applicationIdSuffix = ".simple"
            versionNameSuffix = "-simple"
            buildConfigField("String", "APP_EXPERIENCE", "\"simple\"")
        }
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
        unitTests.all { task ->
            // Roborazzi captures bitmaps; large screen catalogs (logsScreen,
            // homeCompactScreen, etc.) accumulate heap under the default
            // 512MB test JVM and OOM mid-run. 4GB matches the gradle daemon
            // budget already set in gradle.properties and is enough for the
            // full screenshot catalog.
            task.maxHeapSize = "4g"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)

    implementation(libs.bundles.lifecycle.app)
    implementation(libs.androidx.lifecycle.process)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.runtime.tracing)
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

    // Proto DataStore
    implementation(libs.androidx.datastore)

    implementation(libs.kermit)
    implementation(libs.process.phoenix)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // Glance app widgets
    implementation(libs.bundles.glance)
    implementation(libs.androidx.datastore.preferences)

    // QR scanner: CameraX preview/analysis + ML Kit on-device barcode scanning.
    implementation(libs.bundles.camerax)
    implementation(libs.mlkit.barcode.scanning)
    // QR generation: zxing-core only (no Play Services), offline encoding for profile share.
    implementation(libs.zxing.core)

    testImplementation(libs.bundles.unit.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":core:diagnostics-data"))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
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
    val verifyEmbeddedRelayBundle =
        tasks.register<VerifyEmbeddedRelayBundleTask>("verifyEmbeddedRelayBundle") {
            group = "verification"
            description = "Fails simple builds when the required embedded relay bundle is missing or empty."
            bundleFiles.from(layout.projectDirectory.file("src/simple/assets/embedded-relay-bundle.json"))
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
        onVariants(selector().all()) { variant ->
            if (variant.productFlavors.toMap()["experience"] == "simple") {
                val mergeAssetsTaskName = variant.computeTaskName("merge", "Assets")
                tasks.configureEach {
                    if (name == mergeAssetsTaskName) {
                        dependsOn(verifyEmbeddedRelayBundle)
                    }
                }
            }
        }

        onVariants(selector().withBuildType("release")) { variant ->
            val flavors = variant.productFlavors.toMap()
            writeReleaseIdentityManifest.configure {
                applicationIds.put(variant.name, variant.applicationId)
                distributions.put(variant.name, flavors.getValue("distribution"))
                experiences.put(variant.name, flavors.getValue("experience"))
            }
        }
    }
}

// Guard the actual packaging tasks, including calls through aggregate/custom tasks.
// Unit tests and lint remain native-less; no APK or bundle may contain the throwing stub.
tasks.withType<PackageAndroidArtifact>().configureEach {
    dependsOn(
        if (name.contains("Release", ignoreCase = true)) {
            ":core:engine:verifyLibXrayReleaseArtifacts"
        } else {
            ":core:engine:verifyLibXrayArtifacts"
        },
    )
}
tasks.withType<PackageBundleTask>().configureEach {
    dependsOn(":core:engine:verifyLibXrayReleaseArtifacts")
}
tasks.withType<FinalizeBundleTask>().configureEach {
    dependsOn(":core:engine:verifyLibXrayReleaseArtifacts")
}
