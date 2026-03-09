import com.google.protobuf.gradle.id

plugins {
    id("com.google.protobuf")
}

protobuf {
    protoc {
        artifact = the<org.gradle.api.artifacts.VersionCatalogsExtension>()
            .named("libs")
            .findLibrary("protobuf-protoc").get().get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") { option("lite") }
            }
        }
    }
}
