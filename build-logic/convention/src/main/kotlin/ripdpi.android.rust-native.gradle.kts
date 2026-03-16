import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.inject.Inject

data class RustNativeArtifact(
    val packageName: String,
    val sourceName: String,
    val outputName: String,
)

@CacheableTask
abstract class BuildRustNativeLibsTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val nativeSources: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val workspaceManifest: RegularFileProperty

        @get:Input
        abstract val sdkDir: Property<String>

        @get:Input
        abstract val ndkVersion: Property<String>

        @get:Input
        abstract val cargoProfile: Property<String>

        @get:Input
        abstract val minSdk: Property<Int>

        @get:Input
        abstract val abis: ListProperty<String>

        @get:Input
        abstract val artifactSpecs: ListProperty<String>

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @get:LocalState
        abstract val cargoTargetDir: DirectoryProperty

        @TaskAction
        fun build() {
            val installedTargets = installedRustTargets()
            val hostBinDir = resolveNdkToolchainBinDir()
            val manifest = workspaceManifest.get().asFile
            val artifacts = artifactSpecs.get().map(::parseArtifactSpec)
            val packageNames = artifacts.map(RustNativeArtifact::packageName).distinct()
            val expectedOutputNames = artifacts.map(RustNativeArtifact::outputName).toSet()
            val outputRoot = outputDir.get().asFile
            val cargoTargetRoot = cargoTargetDir.get().asFile
            val cargoExecutable = resolveRustTool("cargo")
            val cargoProfileName = cargoProfile.get()
            val abiList = abis.get()

            pruneStaleAbiOutputs(outputRoot)

            // Validate all ABIs upfront before spawning parallel builds.
            val abiConfigs = abiList.map { abi ->
                val target = abiToRustTarget(abi)
                val clangTarget = abiToClangTarget(abi)
                if (target !in installedTargets) {
                    throw GradleException(
                        "Missing Rust target $target. Install it once with `rustup target add $target`.",
                    )
                }

                val linker = hostBinDir.resolve("${clangTarget}${minSdk.get()}-clang")
                if (!linker.isFile) {
                    throw GradleException("Android linker not found: ${linker.absolutePath}")
                }
                val ar = hostBinDir.resolve("llvm-ar")
                if (!ar.isFile) {
                    throw GradleException("Android archiver not found: ${ar.absolutePath}")
                }

                AbiConfig(abi, target, linker, ar)
            }

            // Build all ABIs in parallel (each ABI has its own CARGO_TARGET_DIR).
            // Cap thread count to available processors to avoid CPU contention.
            val availableCpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threadCount = abiConfigs.size.coerceAtMost(availableCpus)
            val cargoJobs = (availableCpus / abiConfigs.size).coerceAtLeast(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            try {
                val futures: List<Future<*>> = abiConfigs.map { config ->
                    executor.submit {
                        buildSingleAbi(
                            config, manifest, packageNames, cargoExecutable,
                            cargoProfileName, cargoTargetRoot, outputRoot,
                            expectedOutputNames, artifacts, cargoJobs,
                        )
                    }
                }

                // Collect all results; report first failure.
                val errors = mutableListOf<Throwable>()
                for (future in futures) {
                    try {
                        future.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        errors.add(e.cause ?: e)
                    }
                }
                if (errors.isNotEmpty()) {
                    val combined = errors.drop(1).fold(errors.first()) { acc, e -> acc.addSuppressed(e); acc }
                    throw combined
                }
            } finally {
                executor.shutdown()
            }
        }

        private fun buildSingleAbi(
            config: AbiConfig,
            manifest: File,
            packageNames: List<String>,
            cargoExecutable: String,
            cargoProfileName: String,
            cargoTargetRoot: File,
            outputRoot: File,
            expectedOutputNames: Set<String>,
            artifacts: List<RustNativeArtifact>,
            cargoJobs: Int,
        ) {
            val targetEnv = config.target.replace('-', '_').uppercase()
            val ccTargetKey = "CC_${config.target.replace('-', '_')}"
            val arTargetKey = "AR_${config.target.replace('-', '_')}"
            val abiCargoTargetDir = cargoTargetRoot.resolve(config.abi)
            val abiOutputDir = outputRoot.resolve(config.abi)
            abiOutputDir.mkdirs()
            pruneStaleArtifactOutputs(abiOutputDir, expectedOutputNames)

            val cargoEnvironment =
                mapOf(
                    "CC_$targetEnv" to config.linker.absolutePath,
                    ccTargetKey to config.linker.absolutePath,
                    "AR_$targetEnv" to config.ar.absolutePath,
                    arTargetKey to config.ar.absolutePath,
                    "CARGO_TARGET_${targetEnv}_LINKER" to config.linker.absolutePath,
                    "CARGO_TARGET_${targetEnv}_AR" to config.ar.absolutePath,
                    "CARGO_TARGET_DIR" to abiCargoTargetDir.absolutePath,
                )

            val cargoCommand =
                buildList {
                    add(cargoExecutable)
                    add("build")
                    add("--manifest-path")
                    add(manifest.absolutePath)
                    for (packageName in packageNames) {
                        add("-p")
                        add(packageName)
                    }
                    add("--locked")
                    add("--target")
                    add(config.target)
                    add("--profile")
                    add(cargoProfileName)
                    add("--jobs")
                    add(cargoJobs.toString())
                }

            execOperations.exec {
                workingDir = manifest.parentFile
                environment(cargoEnvironment)
                commandLine(cargoCommand)
            }.assertNormalExitValue()

            for (artifact in artifacts) {
                val builtLibrary = abiCargoTargetDir.resolve("${config.target}/$cargoProfileName/${artifact.sourceName}")
                if (!builtLibrary.isFile) {
                    throw GradleException(
                        "Expected native library was not produced: ${builtLibrary.absolutePath}",
                    )
                }

                val packagedLibrary = abiOutputDir.resolve(artifact.outputName)
                copyIfChanged(builtLibrary, packagedLibrary)
            }
        }

        private data class AbiConfig(
            val abi: String,
            val target: String,
            val linker: File,
            val ar: File,
        )

        private fun resolveNdkToolchainBinDir(): File {
            val ndkDir = File(sdkDir.get()).resolve("ndk").resolve(ndkVersion.get())
            val toolchainsDir = ndkDir.resolve("toolchains/llvm/prebuilt")
            val hostTag =
                listOf("linux-x86_64", "darwin-arm64", "darwin-x86_64")
                    .firstOrNull { toolchainsDir.resolve(it).isDirectory }
                    ?: throw GradleException("Unsupported NDK host toolchain layout in ${toolchainsDir.absolutePath}")
            return toolchainsDir.resolve(hostTag).resolve("bin")
        }

        private fun installedRustTargets(): Set<String> {
            val stdout = ByteArrayOutputStream()
            val rustupExecutable = resolveRustTool("rustup")
            execOperations.exec {
                standardOutput = stdout
                commandLine(rustupExecutable, "target", "list", "--installed")
            }.assertNormalExitValue()

            return stdout.toString()
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        }

        private fun pruneStaleAbiOutputs(outputRoot: File) {
            if (!outputRoot.isDirectory) {
                return
            }

            val activeAbis = abis.get().toSet()
            val stale = outputRoot.listFiles()?.filter { it.isDirectory && it.name !in activeAbis }.orEmpty()
            if (stale.isEmpty()) {
                return
            }

            fileSystemOperations.delete {
                delete(stale)
            }
        }

        private fun pruneStaleArtifactOutputs(abiOutputDir: File, expectedOutputNames: Set<String>) {
            if (!abiOutputDir.isDirectory) {
                return
            }

            val stale =
                abiOutputDir.listFiles()
                    ?.filter { candidate -> candidate.isFile && candidate.name !in expectedOutputNames }
                    .orEmpty()
            if (stale.isEmpty()) {
                return
            }

            fileSystemOperations.delete {
                delete(stale)
            }
        }

        private fun copyIfChanged(source: File, target: File) {
            target.parentFile.mkdirs()
            if (target.isFile && Files.mismatch(source.toPath(), target.toPath()) == -1L) {
                return
            }

            source.copyTo(target, overwrite = true)
        }

        private fun parseArtifactSpec(value: String): RustNativeArtifact {
            val parts = value.split('|')
            require(parts.size == 3) { "Invalid Rust native artifact spec: $value" }
            return RustNativeArtifact(
                packageName = parts[0],
                sourceName = parts[1],
                outputName = parts[2],
            )
        }

        private fun abiToRustTarget(abi: String): String =
            when (abi) {
                "armeabi-v7a" -> "armv7-linux-androideabi"
                "arm64-v8a" -> "aarch64-linux-android"
                "x86" -> "i686-linux-android"
                "x86_64" -> "x86_64-linux-android"
                else -> throw GradleException("Unsupported ABI: $abi")
            }

        private fun abiToClangTarget(abi: String): String =
            when (abi) {
                "armeabi-v7a" -> "armv7a-linux-androideabi"
                "arm64-v8a" -> "aarch64-linux-android"
                "x86" -> "i686-linux-android"
                "x86_64" -> "x86_64-linux-android"
                else -> throw GradleException("Unsupported ABI: $abi")
            }

        private fun resolveRustTool(name: String): String {
            val cargoHome = System.getenv("CARGO_HOME")?.takeIf(String::isNotBlank)
            val homeDir = System.getProperty("user.home")
            val candidates =
                listOfNotNull(
                    cargoHome?.let { File(it).resolve("bin").resolve(name) },
                    homeDir.takeIf(String::isNotBlank)?.let { File(it).resolve(".cargo").resolve("bin").resolve(name) },
                )

            return candidates.firstOrNull(File::canExecute)?.absolutePath
                ?: name
        }
    }

val rustNativeAbis = resolvedNativeAbis()
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val rustNativeBuildDir = layout.buildDirectory.dir("intermediates/rust")

extensions.configure<LibraryExtension> {
    sourceSets["main"].jniLibs.directories.add(generatedJniLibsDir.get().asFile.absolutePath)
}

val buildRustNativeLibs =
    tasks.register<BuildRustNativeLibsTask>("buildRustNativeLibs") {
        group = "build"
        description = "Builds Rust native libraries into Gradle-managed jniLibs outputs."

        nativeSources.from(
            fileTree(rootProject.file("native/rust")) {
                exclude("**/target/**")
            },
        )
        workspaceManifest.set(rootProject.layout.projectDirectory.file("native/rust/Cargo.toml"))
        sdkDir.set(resolveAndroidSdkDir())
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cargoProfile.set(providers.gradleProperty("ripdpi.nativeCargoProfile"))
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        artifactSpecs.set(
            listOf(
                "ripdpi-android|libripdpi_android.so|libripdpi.so",
                "hs5t-android|libhs5t_android.so|libhev-socks5-tunnel.so",
            ),
        )
        cargoTargetDir.set(rustNativeBuildDir)
        outputDir.set(generatedJniLibsDir)
    }

tasks.named("preBuild") {
    dependsOn(buildRustNativeLibs)
}
