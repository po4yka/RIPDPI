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

// libXray Android artifact wiring (Xray provider mode).
//
// The gomobile-built AAR + per-ABI .so payloads are produced out-of-band by
// scripts/native/build-libxray.sh into an ignored directory (default
// native/xray/artifacts, overridable via -Pripdpi.prebuiltXrayAarDir=...).
// They are NEVER committed — only their location is wired in as a Gradle
// input so the verification gate can run as part of the build graph.
//
// `verifyLibXrayArtifacts` shells out to the pure-shell verify script, which
// fails on missing ABIs, version drift vs gradle/libs.versions.toml, or an
// oversized native payload. When linking is ON (see `linkXray` below) it is
// attached to `preBuild` so a missing/drifted/oversized artifact FAILS THE
// BUILD; when linking is OFF (the offline/native-less default) it is detached so
// builds with no NDK 29 / gomobile keep working and may still run it explicitly.
//
// NOTE: these vals are declared BEFORE the `extensions.configure<LibraryExtension>`
// block below because that block's source-set selection reads `linkXray`
// (top-level script vals initialize in source order, so an out-of-order read
// would silently see the uninitialized default and always pick the stub).
val xrayArtifactDir =
    providers
        .gradleProperty("ripdpi.prebuiltXrayAarDir")
        .map(::File)
        .orElse(
            rootProject.layout.projectDirectory
                .dir("native/xray/artifacts")
                .asFile,
        )

// libXray linking detection. Mirrors the rust .so prebuilt-dir idiom: the AAR is
// produced out-of-band and is gitignored, so linking is OFF by default (offline
// / native-less CI keeps compiling the stub). Linking turns ON when either:
//   - the resolved artifact directory contains a real `libxray.aar`, OR
//   - the explicit opt-in `-Pripdpi.linkXray=true` is passed.
// When opted in without an artifact, the verify gate fails the build (intended).
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
val linkXray = hasXrayAar || linkXrayOptIn

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

tasks.register<Exec>("verifyLibXrayArtifacts") {
    group = "verification"
    description = "Verifies the gomobile-built libXray artifact against the pinned versions and size budget."
    val verifyScript =
        rootProject.layout.projectDirectory
            .file("scripts/native/verify-libxray-artifacts.sh")
            .asFile
    inputs.file(verifyScript)
    inputs.file(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
    inputs.dir(xrayArtifactDir).optional(true).withPropertyName("xrayArtifactDir")
    environment("RIPDPI_XRAY_AAR_DIR", xrayArtifactDir.get().absolutePath)
    commandLine("bash", verifyScript.absolutePath)
    // Reject canary-channel artifacts when a release-like task is in the graph.
    val releaseLike =
        gradle.startParameter.taskNames.any {
            val n = it.lowercase()
            n.contains("release") || n.contains("bundle") || n.contains("publish")
        }
    if (releaseLike) {
        args("--release")
    }
}

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
