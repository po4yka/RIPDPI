import com.android.build.api.dsl.LibraryExtension
import java.io.File

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.hilt")
    id("ripdpi.android.quality")
    id("ripdpi.android.serialization")
    id("ripdpi.android.rust-native")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.engine"
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
// oversized native payload. It is intentionally NOT wired into `assemble` so
// offline/native-less builds (no NDK 29, no gomobile) keep working; CI and
// release packaging invoke it explicitly.
val xrayArtifactDir =
    providers
        .gradleProperty("ripdpi.prebuiltXrayAarDir")
        .map(::File)
        .orElse(
            rootProject.layout.projectDirectory
                .dir("native/xray/artifacts")
                .asFile,
        )

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
}
