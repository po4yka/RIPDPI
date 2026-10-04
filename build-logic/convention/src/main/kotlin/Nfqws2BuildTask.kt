import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

@CacheableTask
abstract class Nfqws2BuildTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val vendorDir: DirectoryProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val builderScript: RegularFileProperty

        @get:Internal
        abstract val sdkDir: DirectoryProperty

        @get:Input
        abstract val ndkVersion: Property<String>

        @get:Input
        abstract val minSdk: Property<Int>

        @get:Input
        abstract val abis: ListProperty<String>

        @get:Input
        abstract val jobs: Property<Int>

        @get:LocalState
        abstract val workDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            val buildJobs = jobs.get()
            require(buildJobs > 0) { "nfqws2 build jobs must be at least 1" }
            fileSystemOperations.delete { delete(outputDir) }
            execOperations.exec {
                commandLine(
                    "python3",
                    builderScript.get().asFile.absolutePath,
                    "--platform",
                    "android",
                    "--abis",
                    abis.get().joinToString(","),
                    "--api",
                    minSdk.get().toString(),
                    "--jobs",
                    buildJobs.toString(),
                    "--ndk",
                    sdkDir
                        .get()
                        .dir("ndk/${ndkVersion.get()}")
                        .asFile.absolutePath,
                    "--work",
                    workDir.get().asFile.absolutePath,
                    "--output",
                    outputDir.get().asFile.absolutePath,
                )
            }
        }
    }
