import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("io.github.takahirom.roborazzi")
}

val libs = versionCatalogs.named("libs")

plugins.withId("com.android.application") {
    extensions.configure<ApplicationExtension> {
        testOptions {
            unitTests.isIncludeAndroidResources = true
        }
    }
}

plugins.withId("com.android.library") {
    extensions.configure<LibraryExtension> {
        testOptions {
            unitTests.isIncludeAndroidResources = true
        }
    }
}

dependencies {
    add("testImplementation", libs.findLibrary("roborazzi").get())
    add("testImplementation", libs.findLibrary("roborazzi-compose").get())
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots").asFile)
}
