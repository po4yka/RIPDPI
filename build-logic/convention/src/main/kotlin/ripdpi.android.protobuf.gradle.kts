import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
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
        val protoFiles =
            protoRoot
                .walkTopDown()
                .filter {
                    it.isFile && it.extension == "proto"
                }.sortedBy(File::getPath)
                .toList()
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

        execOperations
            .exec {
                executable = protoc.absolutePath
                args(
                    "--proto_path=${protoRoot.absolutePath}",
                    "--java_out=lite:${output.absolutePath}",
                )
                args(protoFiles.map(File::getAbsolutePath))
            }.assertNormalExitValue()
    }
}

fun detectProtocClassifier(
    osName: String,
    osArch: String,
): String {
    val normalizedOsName = osName.lowercase()
    val normalizedOsArch = osArch.lowercase()

    fun normalizedArch(): String =
        when (normalizedOsArch) {
            "x86_64", "amd64" -> "x86_64"
            "aarch64", "arm64" -> "aarch_64"
            else -> error("Unsupported architecture for protoc: $normalizedOsArch")
        }

    return when {
        normalizedOsName.contains("mac") -> "osx-${normalizedArch()}"
        normalizedOsName.contains("linux") -> "linux-${normalizedArch()}"
        normalizedOsName.contains("windows") -> "windows-${normalizedArch()}"
        else -> error("Unsupported operating system for protoc: $normalizedOsName")
    }
}

val libs = the<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
val protobufVersion = libs.findVersion("protobuf").get().requiredVersion
val protocClassifier =
    detectProtocClassifier(
        providers.systemProperty("os.name").get(),
        providers.systemProperty("os.arch").get(),
    )
val protocConfiguration =
    configurations
        .detachedConfiguration(
            dependencies.create("com.google.protobuf:protoc:$protobufVersion:$protocClassifier@exe"),
        ).apply {
            isTransitive = false
        }
val protoLiteSourceDirectories = objects.mapProperty(String::class.java, Directory::class.java)
extensions.add("protoLiteSourceDirectories", protoLiteSourceDirectories)

fun registerProtoGenerator(
    name: String,
    outputName: String,
    source: Provider<Directory>,
): TaskProvider<GenerateProtoLiteSourcesTask> =
    tasks.register<GenerateProtoLiteSourcesTask>(name) {
        group = "build"
        description = "Generates Java lite sources from the selected protobuf schema."

        protocBinary.set(layout.file(providers.provider { protocConfiguration.singleFile }))
        protoSourceDir.set(source)
        outputDir.set(layout.buildDirectory.dir("generated/source/protoLite/$outputName/java"))
    }

val generateProtoLiteSources =
    registerProtoGenerator(
        name = "generateProtoLiteSources",
        outputName = "main",
        source = providers.provider { layout.projectDirectory.dir("src/main/proto") },
    )
val alternateGenerators = mutableMapOf<String, TaskProvider<GenerateProtoLiteSourcesTask>>()

extensions.configure<LibraryAndroidComponentsExtension> {
    onVariants(selector().all()) { variant ->
        val alternateFlavor =
            variant.productFlavors
                .firstOrNull { (_, flavor) ->
                    protoLiteSourceDirectories.getting(flavor).isPresent
                }?.second
        val generator =
            alternateFlavor?.let { flavor ->
                alternateGenerators.getOrPut(flavor) {
                    registerProtoGenerator(
                        name = "generate${flavor.replaceFirstChar(Char::uppercaseChar)}ProtoLiteSources",
                        outputName = flavor,
                        source = protoLiteSourceDirectories.getting(flavor),
                    )
                }
            } ?: generateProtoLiteSources
        variant.sources.java?.addGeneratedSourceDirectory(generator, GenerateProtoLiteSourcesTask::outputDir)
    }
}
