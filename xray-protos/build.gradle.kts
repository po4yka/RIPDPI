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

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val grpcVersion = libsCatalog.findVersion("grpc").get().requiredVersion
val protobufVersion = libsCatalog.findVersion("protobuf").get().requiredVersion

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
