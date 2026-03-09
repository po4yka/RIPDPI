import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("ripdpi.android.native")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
