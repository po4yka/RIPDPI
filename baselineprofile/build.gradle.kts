import com.android.build.api.variant.TestAndroidComponentsExtension

plugins {
    id("ripdpi.android.test")
}

android {
    namespace = "com.poyka.ripdpi.baselineprofile"
    compileSdk = providers.gradleProperty("ripdpi.compileSdk").get().toInt()

    defaultConfig {
        minSdk = 28
        targetSdk = providers.gradleProperty("ripdpi.targetSdk").get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("distribution", "github")
        missingDimensionStrategy("runtimeEdition", "extended")
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "EMULATOR,DEBUGGABLE,NOT-SELF-INSTRUMENTING"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") {
            initWith(getByName("debug"))
            matchingFallbacks += "release"
        }
    }
}

extensions.configure<TestAndroidComponentsExtension> {
    onVariants(selector().withBuildType("benchmark")) { variant ->
        variant.instrumentationRunnerArguments.put("androidx.benchmark.suppressErrors", "EMULATOR")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
