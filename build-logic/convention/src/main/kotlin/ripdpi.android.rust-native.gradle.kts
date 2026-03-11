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
            val outputRoot = outputDir.get().asFile
            val cargoTargetRoot = cargoTargetDir.get().asFile
            val cargoExecutable = resolveRustTool("cargo")
            val cargoProfileName = cargoProfile.get()

            pruneStaleAbiOutputs(outputRoot)

            for (abi in abis.get()) {
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

                val targetEnv = target.replace('-', '_').uppercase()
                val ccTargetKey = "CC_${target.replace('-', '_')}"
                val arTargetKey = "AR_${target.replace('-', '_')}"
                val abiCargoTargetDir = cargoTargetRoot.resolve(abi)
                val abiOutputDir = outputRoot.resolve(abi)
                abiOutputDir.mkdirs()

                val cargoEnvironment =
                    mapOf(
                        "CC_$targetEnv" to linker.absolutePath,
                        ccTargetKey to linker.absolutePath,
                        "AR_$targetEnv" to ar.absolutePath,
                        arTargetKey to ar.absolutePath,
                        "CARGO_TARGET_${targetEnv}_LINKER" to linker.absolutePath,
                        "CARGO_TARGET_${targetEnv}_AR" to ar.absolutePath,
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
                        add("--target")
                        add(target)
                        add("--profile")
                        add(cargoProfileName)
                    }

                execOperations.exec {
                    workingDir = manifest.parentFile
                    environment(cargoEnvironment)
                    commandLine(cargoCommand)
                }.assertNormalExitValue()

                for (artifact in artifacts) {
                    val builtLibrary = abiCargoTargetDir.resolve("$target/$cargoProfileName/${artifact.sourceName}")
                    if (!builtLibrary.isFile) {
                        throw GradleException(
                            "Expected native library was not produced: ${builtLibrary.absolutePath}",
                        )
                    }

                    val packagedLibrary = abiOutputDir.resolve(artifact.outputName)
                    copyIfChanged(builtLibrary, packagedLibrary)
                }
            }
        }

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
