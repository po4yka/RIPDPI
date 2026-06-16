plugins {
    `java-library`
    id("com.android.lint")
    alias(libs.plugins.google.protobuf)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val grpcVersion = libs.versions.grpc.get()
val protobufVersion = libs.versions.protobuf.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") {
                    option("lite")
                }
            }
            task.plugins {
                maybeCreate("grpc").apply {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(libs.grpc.stub)
    api(libs.grpc.protobuf.lite)
    api(libs.protobuf.javalite)
    compileOnly(libs.javax.annotation.api)
}
