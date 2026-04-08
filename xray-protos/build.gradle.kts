plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.6"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val grpcVersion = "1.64.0"
val protobufVersion = "4.34.1"

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
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-protobuf-lite:$grpcVersion")
    api("com.google.protobuf:protobuf-javalite:$protobufVersion")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}
