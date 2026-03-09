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
val nativeCmakeVersion = providers.gradleProperty("ripdpi.nativeCmakeVersion")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val ndkProjectDir = layout.buildDirectory.dir("intermediates/ndkBuild")

android {
    namespace = "com.poyka.ripdpi.core.engine"

    sourceSets["main"].jniLibs.srcDir(generatedJniLibsDir.get().asFile)

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = nativeCmakeVersion.get()
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_PLATFORM=android-${nativeMinSdk.get()}"
            }
        }
    }
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

tasks.register<Exec>("runNdkBuild") {
    group = "build"
    description = "Builds hev-socks5-tunnel into Gradle-managed jniLibs outputs."

    inputs.files(
        fileTree("src/main/jni") {
            exclude("**/obj/**", "**/libs/**")
        },
    )
    inputs.property("nativeAbis", nativeAbis)
    inputs.property("nativeMinSdk", nativeMinSdk)
    outputs.dir(generatedJniLibsDir)

    val ndkDir = file("$sdkDir/ndk/${nativeNdkVersion.get()}")

    executable =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "$ndkDir\\ndk-build.cmd"
        } else {
            "$ndkDir/ndk-build"
        }
    setArgs(
        listOf(
            "NDK_PROJECT_PATH=${ndkProjectDir.get().asFile.invariantSeparatorsPath}",
            "NDK_LIBS_OUT=${generatedJniLibsDir.get().asFile.invariantSeparatorsPath}",
            "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
            "APP_PLATFORM=android-${nativeMinSdk.get()}",
            "APP_ABI=${nativeAbis.get().joinToString(" ")}",
            "APP_CFLAGS=-O3 -DPKGNAME=com/poyka/ripdpi/core",
            "APP_CPPFLAGS=-O3 -std=c++11",
            "APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true",
        ),
    )

    doFirst {
        generatedJniLibsDir.get().asFile.deleteRecursively()
        ndkProjectDir.get().asFile.deleteRecursively()
    }
}

tasks.named("preBuild") {
    dependsOn("runNdkBuild")
}
