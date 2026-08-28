import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.serialization")
    id("ripdpi.android.rust-native")
}

// Real libXray is required for packaging. Native-less validation may compile the explicit stub.
// Source-patched AARs live in an ignored producer directory and are verified before use.
val xrayArtifactDir =
    providers
        .gradleProperty("ripdpi.prebuiltXrayAarDir")
        .map(::File)
        .orElse(
            rootProject.layout.projectDirectory
                .dir("native/xray/artifacts")
                .asFile,
        )

val xrayAarFile = xrayArtifactDir.get().resolve("libxray.aar")
val hasXrayAar =
    providers
        .of(XrayAarPresenceValueSource::class.java) {
            parameters.aarPath.set(xrayAarFile.absolutePath)
        }.get()
val linkXrayOptIn =
    providers
        .gradleProperty("ripdpi.linkXray")
        .orNull
        ?.toBooleanStrictOrNull()
        ?: false
// Only explicitly native-less validation may compile the stub. Shipping builds require the AAR.
val nativeLessValidation = providers.gradleProperty("ripdpi.skipNativeBuild").orNull == "true"
val linkXray = hasXrayAar || linkXrayOptIn || !nativeLessValidation

// Config-cache-correct AAR presence check. A raw File.isFile() at configuration
// time is NOT tracked by the configuration cache, so a cached linking decision
// could go stale when the gitignored artifact appears/disappears between builds.
// A ValueSource is re-evaluated every build, so toggling the artifact invalidates
// the cache and re-selects the correct source set.
abstract class XrayAarPresenceValueSource : ValueSource<Boolean, XrayAarPresenceValueSource.Params> {
    interface Params : ValueSourceParameters {
        val aarPath: Property<String>
    }

    override fun obtain(): Boolean = File(parameters.aarPath.get()).isFile
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.engine"

    // Exactly ONE of xrayLinked / xrayStub is added to `main`, so the FQN
    // com.poyka.ripdpi.core.XrayNativeBridgeLibXrayImpl is defined once. The
    // engine-api throwing stub was removed in the same change to avoid a
    // duplicate-FQN collision.
    if (linkXray) {
        sourceSets
            .getByName("main")
            .kotlin.directories
            .add("src/xrayLinked/kotlin")
        // Gated unit tests for the real impl's pure logic (CallResponse parsing,
        // protect adapter). They need libXray.DialerController on the unit-test
        // classpath but must NEVER classload libXray.LibXray (gojni). AGP's
        // built-in Kotlin compiles unit tests from the `test` source set's
        // kotlin directories.
        sourceSets
            .getByName("test")
            .kotlin.directories
            .add("src/testXrayLinked/kotlin")
    } else {
        sourceSets
            .getByName("main")
            .kotlin.directories
            .add("src/xrayStub/kotlin")
    }
}

val packagedXrayAbis =
    extensions
        .getByType<LibraryExtension>()
        .defaultConfig.ndk.abiFilters
        .sorted()
        .joinToString(",")

fun registerXrayVerifier(
    taskName: String,
    release: Boolean,
) = tasks.register<Exec>(taskName) {
    group = "verification"
    description = "Verifies linked Xray API, ELF and content-bound source/build provenance."
    val verifyScript =
        rootProject.layout.projectDirectory
            .file("scripts/native/verify-libxray-artifacts.sh")
            .asFile
    inputs.files(
        verifyScript,
        rootProject.file("scripts/native/libxray_artifacts.py"),
        rootProject.file("scripts/native/build-libxray.sh"),
        rootProject.file("gradle.properties"),
        rootProject.file("gradle/libs.versions.toml"),
    )
    inputs.dir(rootProject.file("native/xray/patches"))
    inputs.dir(xrayArtifactDir).optional(true).withPropertyName("xrayArtifactDir")
    inputs.property("linkXray", linkXray)
    inputs.property("selectedAbis", packagedXrayAbis)
    inputs.property("release", release)
    doFirst {
        check(inputs.properties["linkXray"] == true) { "APK/AAB packaging requires the real libXray runtime" }
    }
    environment("RIPDPI_XRAY_AAR_DIR", xrayArtifactDir.get().absolutePath)
    commandLine("bash", verifyScript.absolutePath)
    if (release) args("--release") else args("--abis", packagedXrayAbis)
}

registerXrayVerifier("verifyLibXrayArtifacts", release = false)
registerXrayVerifier("verifyLibXrayReleaseArtifacts", release = true)

dependencies {
    api(project(":core:engine-api"))
    implementation(project(":core:data"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kermit)

    testImplementation(libs.bundles.unit.test)

    // When linking is ON, put the gomobile AAR on both the main and unit-test
    // classpaths. AGP unpacks the .aar: classes.jar onto the classpath and merges
    // jni/<abi>/*.so into consumers. testImplementation exposes the pure
    // libXray.DialerController interface to the gated tests (which never touch
    // libXray.LibXray). When OFF, neither is added so offline builds need no AAR.
    if (linkXray) {
        implementation(files(xrayAarFile))
        testImplementation(files(xrayAarFile))
    }
}

// Attach the verification gate to the build ONLY when linking. A missing /
// drifted / oversized / incomplete artifact then FAILS THE BUILD via preBuild.
// With -Pripdpi.linkXray=true but no artifact, the verify script exits non-zero
// and the build fails (intended hard error). Offline (linking OFF) leaves the
// gate detached so native-less builds keep working.
if (linkXray) {
    tasks.named("preBuild") {
        dependsOn("verifyLibXrayArtifacts")
    }
}
