import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.poyka.ripdpi.core.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 27
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.core)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val sdkDir = localProperties.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: throw GradleException("SDK location not found. Set sdk.dir in local.properties or ANDROID_HOME env var.")

tasks.register<Exec>("runNdkBuild") {
    group = "build"

    val ndkDir = file("$sdkDir/ndk/${android.ndkVersion}")

    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    setArgs(listOf(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        "NDK_LIBS_OUT=src/main/jniLibs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk"
    ))

    println("Command: $commandLine")
}

tasks.preBuild {
    dependsOn("runNdkBuild")
}
