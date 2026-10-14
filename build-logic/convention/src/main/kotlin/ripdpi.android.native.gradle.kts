import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

val nativeAbis = resolvedNativeAbis()
val nativeNdkVersion = providers.gradleProperty("ripdpi.nativeNdkVersion")

// Helper executables (pluggable transports, NaiveProxy, Cloudflare helpers) ship as lib<name>.so
// and run from ApplicationInfo.nativeLibraryDir. Android 10+ forbids exec() from app data
// directories, so the package manager must extract native libraries at install time
// (android:extractNativeLibs="true"). Do not turn this off.
val nativeLibrariesExtractedAtInstall = true

fun ApplicationExtension.configureNativePolicy() {
    ndkVersion = nativeNdkVersion.get()

    defaultConfig {
        ndk {
            abiFilters.clear()
            abiFilters.addAll(nativeAbis)
        }
    }

    packaging {
        resources {
            excludes += "DebugProbesKt.bin"
        }
        jniLibs {
            useLegacyPackaging = nativeLibrariesExtractedAtInstall
            keepDebugSymbols += "**/*.so"
        }
    }
}

fun LibraryExtension.configureNativePolicy() {
    ndkVersion = nativeNdkVersion.get()

    defaultConfig {
        ndk {
            abiFilters.clear()
            abiFilters.addAll(nativeAbis)
        }
    }

    packaging {
        resources {
            excludes += "DebugProbesKt.bin"
        }
        jniLibs {
            useLegacyPackaging = nativeLibrariesExtractedAtInstall
            keepDebugSymbols += "**/*.so"
        }
    }
}

plugins.withId("com.android.application") {
    extensions.configure<ApplicationExtension> {
        configureNativePolicy()
    }
}

plugins.withId("com.android.library") {
    extensions.configure<LibraryExtension> {
        configureNativePolicy()
    }
}
