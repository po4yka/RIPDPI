import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

val nativeAbis =
    providers.gradleProperty("ripdpi.nativeAbis").map { value ->
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
val nativeNdkVersion = providers.gradleProperty("ripdpi.nativeNdkVersion")
val nativeUseLegacyPackaging =
    providers.gradleProperty("ripdpi.nativeUseLegacyPackaging").map(String::toBoolean)

fun ApplicationExtension.configureNativePolicy() {
    ndkVersion = nativeNdkVersion.get()

    defaultConfig {
        ndk {
            abiFilters.clear()
            abiFilters.addAll(nativeAbis.get())
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = nativeUseLegacyPackaging.get()
        }
    }
}

fun LibraryExtension.configureNativePolicy() {
    ndkVersion = nativeNdkVersion.get()

    defaultConfig {
        ndk {
            abiFilters.clear()
            abiFilters.addAll(nativeAbis.get())
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = nativeUseLegacyPackaging.get()
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
