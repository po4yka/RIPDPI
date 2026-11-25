import com.android.build.api.dsl.TestExtension
import com.android.build.api.variant.TestAndroidComponentsExtension

plugins {
    id("com.android.test")
}

extensions.configure<TestExtension> {
    configureRipDpiManagedDevices(includeCiDevices = false)
}

extensions.configure<TestAndroidComponentsExtension> {
    finalizeDsl { extension ->
        extension.compileSdkMinor = providers.gradleProperty("ripdpi.compileSdkMinor").get().toInt()
    }
}
