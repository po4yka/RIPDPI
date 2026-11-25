import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val kspExecutionLock =
    gradle.sharedServices.registerIfAbsent("ripdpiKspExecutionLock", KspExecutionLock::class.java) {
        maxParallelUsages.set(1)
    }
tasks.withType<KspAATask>().configureEach {
    usesService(kspExecutionLock)
}

dependencies {
    add("implementation", libs.findLibrary("hilt-android").get())
    add("androidTestImplementation", libs.findLibrary("hilt-android-testing").get())
    add("ksp", libs.findLibrary("hilt-compiler").get())
    add("kspAndroidTest", libs.findLibrary("hilt-compiler").get())
    add("ksp", libs.findLibrary("kotlin-metadata-jvm").get())
    add("kspAndroidTest", libs.findLibrary("kotlin-metadata-jvm").get())
}
