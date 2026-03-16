import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.api.ApkVariantOutput
import com.android.build.gradle.internal.tasks.FinalizeBundleTask
import java.util.Locale
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("ripdpi.android.native")
    id("ripdpi.android.jacoco")
    id("ripdpi.android.lint")
    id("ripdpi.android.detekt")
    id("ripdpi.android.ktlint")
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

    applicationVariants.configureEach {
        val versionName = requireNotNull(mergedFlavor.versionName) { "versionName is required for artifact naming." }
        val versionCode = mergedFlavor.versionCode
        val buildTypeName = buildType.name
        val artifactBaseName = "RIPDPI-$versionName-$versionCode-$buildTypeName"

        outputs.configureEach {
            (this as? ApkVariantOutput)?.outputFileName = "$artifactBaseName.apk"
        }

        val bundleTaskName =
            "sign${name.replaceFirstChar { firstChar -> firstChar.titlecase(Locale.US) }}Bundle"
        project.tasks.named(bundleTaskName, FinalizeBundleTask::class.java).configure {
            finalBundleFile.set(
                project.layout.buildDirectory.file("outputs/bundle/$dirName/$artifactBaseName.aab"),
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
