import com.android.build.api.dsl.LibraryExtension

plugins {
    id("ripdpi.android.library")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.testing.socketbindprobe"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
