import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.VariantOutputConfiguration
import com.android.build.gradle.internal.tasks.FinalizeBundleTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("ripdpi.android.native")
}

extensions.configure<ApplicationExtension> {
    compileSdk = providers.gradleProperty("ripdpi.compileSdk").get().toInt()

    defaultConfig {
        minSdk = providers.gradleProperty("ripdpi.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("ripdpi.targetSdk").get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().all()) { variant ->
        val mainOutput =
            variant.outputs.firstOrNull { output ->
                output.outputType == VariantOutputConfiguration.OutputType.SINGLE
            } ?: return@onVariants

        val versionName =
            requireNotNull(mainOutput.versionName.orNull) {
                "versionName is required for artifact naming."
            }
        val versionCode = requireNotNull(mainOutput.versionCode.orNull) {
            "versionCode is required for artifact naming."
        }
        val buildTypeName = variant.buildType ?: "release"
        val artifactBaseName = "RIPDPI-$versionName-$versionCode-$buildTypeName"

        // AGP 9 no longer exposes APK file renaming on the public variant API.
        val bundleTaskName = variant.computeTaskName("sign", "Bundle")
        project.tasks.withType(FinalizeBundleTask::class.java).configureEach {
            if (name != bundleTaskName) return@configureEach
            finalBundleFile.set(
                project.layout.buildDirectory.file("outputs/bundle/${variant.name}/$artifactBaseName.aab"),
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
