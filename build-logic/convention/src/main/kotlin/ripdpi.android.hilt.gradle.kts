import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// shortcut: KSP 2.3.13 races between tasks; remove after google/ksp#3286 is fixed.
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
    // Hilt's bundled kotlin-metadata-jvm lags new Kotlin releases ("Provided Metadata
    // instance has version X, while maximum supported version is Y"). Dagger >=2.57 unshades
    // it, so we override it with the version matching the Kotlin compiler.
    add("ksp", libs.findLibrary("kotlin-metadata-jvm").get())
    add("kspAndroidTest", libs.findLibrary("kotlin-metadata-jvm").get())
}
