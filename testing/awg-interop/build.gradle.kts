import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.library")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.testing.awginterop"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].jniLibs.directories.add(
        layout.buildDirectory
            .dir("generated/jniLibs")
            .get()
            .asFile.path,
    )
}

dependencies {
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
