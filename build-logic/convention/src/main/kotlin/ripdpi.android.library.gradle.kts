import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("ripdpi.android.native")
}

val consumerProguardRules = project.layout.projectDirectory.file("consumer-rules.pro").asFile

extensions.configure<LibraryExtension> {
    compileSdk = providers.gradleProperty("ripdpi.compileSdk").get().toInt()

    defaultConfig {
        minSdk = providers.gradleProperty("ripdpi.minSdk").get().toInt()
        if (consumerProguardRules.isFile) {
            consumerProguardFiles(consumerProguardRules)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
