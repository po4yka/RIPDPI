import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.library")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.testing.socketbindprobe"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Align with the shared `ripdpi.nativeCmakeVersion` pin (gradle.properties)
            // used by every other native module via ripdpi.android.rust-native; this
            // module previously hardcoded an older, drifted CMake version.
            version = providers.gradleProperty("ripdpi.nativeCmakeVersion").get()
        }
    }
}
