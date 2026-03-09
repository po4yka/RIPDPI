plugins {
    id("com.android.library")
    id("com.google.protobuf")
}

android {
    namespace = "com.poyka.ripdpi.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:4.29.3")
    implementation("androidx.datastore:datastore:1.2.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.17.0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") { option("lite") }
            }
        }
    }
}
