import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

@CacheableTask
abstract class GenerateProtoLiteSourcesTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val protocBinary: org.gradle.api.file.RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protoSourceDir: org.gradle.api.file.DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @TaskAction
    fun generate() {
        val protoRoot = protoSourceDir.get().asFile
        val protoFiles = protoRoot.walkTopDown().filter { it.isFile && it.extension == "proto" }.sortedBy(File::getPath).toList()
        val output = outputDir.get().asFile

        output.deleteRecursively()
        output.mkdirs()

        if (protoFiles.isEmpty()) {
            return
        }

        val protoc = protocBinary.get().asFile
        if (!protoc.canExecute()) {
            protoc.setExecutable(true)
        }

        execOperations.exec {
            executable = protoc.absolutePath
            args(
                "--proto_path=${protoRoot.absolutePath}",
                "--java_out=lite:${output.absolutePath}",
            )
            args(protoFiles.map(File::getAbsolutePath))
        }.assertNormalExitValue()
    }
}

fun detectProtocClassifier(): String {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()

    fun normalizedArch(): String =
        when (osArch) {
            "x86_64", "amd64" -> "x86_64"
            "aarch64", "arm64" -> "aarch_64"
            else -> error("Unsupported architecture for protoc: $osArch")
        }

    return when {
        osName.contains("mac") -> "osx-${normalizedArch()}"
        osName.contains("linux") -> "linux-${normalizedArch()}"
        osName.contains("windows") -> "windows-${normalizedArch()}"
        else -> error("Unsupported operating system for protoc: $osName")
    }
}

val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
val protobufVersion = libs.findVersion("protobuf").get().requiredVersion
val protocConfiguration =
    configurations.detachedConfiguration(
        dependencies.create("com.google.protobuf:protoc:$protobufVersion:${detectProtocClassifier()}@exe"),
    ).apply {
        isTransitive = false
    }
val generatedProtoDir = layout.buildDirectory.dir("generated/source/protoLite/main/java")

extensions.configure<LibraryExtension> {
    sourceSets["main"].java.directories.add(generatedProtoDir.get().asFile.absolutePath)
}

val generateProtoLiteSources =
    tasks.register<GenerateProtoLiteSourcesTask>("generateProtoLiteSources") {
        group = "build"
        description = "Generates Java lite sources from src/main/proto without the protobuf Gradle plugin."

        protocBinary.set(layout.file(providers.provider { protocConfiguration.singleFile }))
        protoSourceDir.set(layout.projectDirectory.dir("src/main/proto"))
        outputDir.set(generatedProtoDir)
    }

tasks.named("preBuild").configure {
    dependsOn(generateProtoLiteSources)
}

tasks.configureEach {
    if (
        name.startsWith("ksp") ||
        (name.startsWith("compile") && (name.endsWith("Kotlin") || name.endsWith("JavaWithJavac")))
    ) {
        dependsOn(generateProtoLiteSources)
    }
}
