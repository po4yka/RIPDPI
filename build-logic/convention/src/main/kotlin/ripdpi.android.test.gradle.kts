import com.android.build.api.dsl.TestExtension

// Convention plugin for `com.android.test` modules (currently `:baselineprofile`).
// Centralizes the shared Gradle Managed Device registry so test/benchmark modules
// run on the same emulators as `:app`.
plugins {
    id("com.android.test")
}

extensions.configure<TestExtension> {
    configureRipDpiManagedDevices(includeCiDevices = false)
}
