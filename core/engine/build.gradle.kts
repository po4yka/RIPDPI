import java.util.Properties

plugins {
    id("ripdpi.android.library")
}

val nativeAbis =
    providers.gradleProperty("ripdpi.nativeAbis").map { value ->
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
val nativeMinSdk = providers.gradleProperty("ripdpi.minSdk")
val nativeNdkVersion = providers.gradleProperty("ripdpi.nativeNdkVersion")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val rustNativeBuildDir = layout.buildDirectory.dir("intermediates/rust")

android {
    namespace = "com.poyka.ripdpi.core.engine"

    sourceSets["main"].jniLibs.srcDir(generatedJniLibsDir.get().asFile)
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.core)
}

val localProperties =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
val sdkDir =
    localProperties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: throw GradleException(
            "SDK location not found. Set sdk.dir in local.properties or ANDROID_SDK_ROOT/ANDROID_HOME env var.",
        )

tasks.register<Exec>("buildRustNativeLibs") {
    group = "build"
    description = "Builds Rust native libraries into Gradle-managed jniLibs outputs."

    val nativeSourceRoots =
        listOf(
            rootProject.file("native/rust"),
            rootProject.file("scripts/native"),
        )

    inputs.files(
        nativeSourceRoots.map { root ->
            fileTree(root) {
                exclude("**/target/**")
            }
        },
    )
    inputs.property("nativeAbis", nativeAbis)
    inputs.property("nativeMinSdk", nativeMinSdk)
    inputs.property("nativeNdkVersion", nativeNdkVersion)
    outputs.dir(generatedJniLibsDir)

    executable = "bash"
    args(
        rootProject.file("scripts/native/build-rust-android.sh").invariantSeparatorsPath,
        "--sdk-dir",
        sdkDir,
        "--ndk-version",
        nativeNdkVersion.get(),
        "--min-sdk",
        nativeMinSdk.get(),
        "--abis",
        nativeAbis.get().joinToString(","),
        "--build-dir",
        rustNativeBuildDir.get().asFile.invariantSeparatorsPath,
        "--output-dir",
        generatedJniLibsDir.get().asFile.invariantSeparatorsPath,
    )

    doFirst {
        generatedJniLibsDir.get().asFile.deleteRecursively()
        rustNativeBuildDir.get().asFile.deleteRecursively()
    }
}

tasks.named("preBuild") {
    dependsOn("buildRustNativeLibs")
}
