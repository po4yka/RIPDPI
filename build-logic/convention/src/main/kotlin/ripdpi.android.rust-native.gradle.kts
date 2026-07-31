import com.android.build.api.dsl.LibraryExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.inject.Inject

data class RustNativeArtifact(
    val packageName: String,
    val sourceName: String,
    val outputName: String,
)

data class PluggableTransportSource(
    val id: String,
    val sourceType: String,
    val repoUrl: String?,
    val commit: String?,
    val goToolchain: String?,
    val cgoEnabled: Boolean,
    val packagePath: String?,
    val packageName: String?,
    val sourceBinaryName: String,
    val outputNames: List<String>,
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
        abstract val cargoExecutable: Property<String>

        @get:Input
        abstract val rustupExecutable: Property<String>

        @get:Input
        abstract val ndkVersion: Property<String>

        @get:Input
        abstract val cmakeVersion: Property<String>

        @get:Input
        abstract val cargoProfile: Property<String>

        @get:Input
        abstract val minSdk: Property<Int>

        @get:Input
        abstract val abis: ListProperty<String>

        @get:Input
        abstract val artifactSpecs: ListProperty<String>

        @get:Input
        abstract val abiParallelism: Property<Int>

        @get:Internal
        abstract val cpuBudget: Property<Int>

        @get:Input
        abstract val forbiddenAmbientCargoOverrideKeys: ListProperty<String>

        @get:Input
        abstract val pruneUnknownArtifacts: Property<Boolean>

        @get:Input
        abstract val stripReleaseOutputs: Property<Boolean>

        // When set (e.g. by CI's build-android-debug job after a per-ABI matrix
        // prebuild), the task copies expected artifacts from
        // `<prebuiltSourceDir>/<abi>/<outputName>` instead of running cargo.
        @get:InputDirectory
        @get:Optional
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val prebuiltSourceDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @get:OutputDirectory
        @get:Optional
        abstract val debugSymbolsDir: DirectoryProperty

        @get:LocalState
        abstract val cargoTargetDir: DirectoryProperty

        init {
            pruneUnknownArtifacts.convention(true)
            stripReleaseOutputs.convention(false)
        }

        @TaskAction
        fun build() {
            val prebuiltPath = prebuiltSourceDir.orNull?.asFile
            if (prebuiltPath != null) {
                copyFromPrebuilt(prebuiltPath)
                return
            }
            rejectAmbientCargoOverrides(forbiddenAmbientCargoOverrideKeys.get())
            val installedTargets = installedRustTargets(rustupExecutable.get())
            val hostBinDir = resolveNdkToolchainBinDir()
            val manifest = workspaceManifest.get().asFile
            val artifacts = artifactSpecs.get().map(::parseArtifactSpec)
            val packageNames = artifacts.map(RustNativeArtifact::packageName).distinct()
            val expectedOutputNames = artifacts.map(RustNativeArtifact::outputName).toSet()
            val shouldPruneUnknownArtifacts = pruneUnknownArtifacts.get()
            val outputRoot = outputDir.get().asFile
            val cargoTargetRoot = cargoTargetDir.get().asFile
            val cargoExecutablePath = cargoExecutable.get()
            val cargoProfileName = cargoProfile.get()
            val shouldStripReleaseOutputs = stripReleaseOutputs.get() && cargoProfileName == "android-jni"
            val debugSymbolsRoot =
                debugSymbolsDir.orNull?.asFile?.takeIf { shouldStripReleaseOutputs }
            val llvmObjcopy = hostBinDir.resolve("llvm-objcopy")
            val llvmStrip = hostBinDir.resolve("llvm-strip")
            if (shouldStripReleaseOutputs) {
                if (!llvmObjcopy.isFile) {
                    throw GradleException("Android llvm-objcopy not found: ${llvmObjcopy.absolutePath}")
                }
                if (!llvmStrip.isFile) {
                    throw GradleException("Android llvm-strip not found: ${llvmStrip.absolutePath}")
                }
            }
            val abiList = abis.get()
            val configuredAbiParallelism = abiParallelism.get()

            // Resolve the Android SDK cmake binary on the Gradle task thread.  Gradle lazy
            // property access (sdkDir.get()) is not safe from background threads, and the
            // cmake crate reads the CMAKE env var to select the cmake binary.  Using the
            // Android SDK cmake avoids macOS sysroot injection by Homebrew cmake.
            val androidSdkCmake = resolvePinnedAndroidSdkCmake().absolutePath
            logger.lifecycle("boring-sys cmake: $androidSdkCmake")

            pruneStaleAbiOutputs(outputRoot)
            debugSymbolsRoot?.let(::pruneStaleAbiOutputs)

            // Validate all ABIs upfront before spawning parallel builds.
            val abiConfigs =
                abiList.map { abi ->
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
                    val cxx = hostBinDir.resolve("${clangTarget}${minSdk.get()}-clang++")
                    if (!cxx.isFile) {
                        throw GradleException("Android C++ linker not found: ${cxx.absolutePath}")
                    }
                    val ar = hostBinDir.resolve("llvm-ar")
                    if (!ar.isFile) {
                        throw GradleException("Android archiver not found: ${ar.absolutePath}")
                    }

                    AbiConfig(abi, target, linker, cxx, ar)
                }

            // Build all ABIs in parallel (each ABI has its own CARGO_TARGET_DIR).
            // Keep Gradle/Cargo child work within the configured native CPU budget.
            val availableCpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val concurrency =
                computeNativeConcurrency(
                    abiCount = abiConfigs.size,
                    availableCpus = availableCpus,
                    abiParallelism = configuredAbiParallelism,
                    cpuBudget = cpuBudget.orNull ?: availableCpus,
                )
            val threadCount = concurrency.abiWorkers
            val cargoJobs = concurrency.cargoJobsPerWorker
            val executor = Executors.newFixedThreadPool(threadCount)
            try {
                val futures: List<Future<*>> =
                    abiConfigs.map { config ->
                        executor.submit {
                            buildSingleAbi(
                                config,
                                manifest,
                                packageNames,
                                cargoExecutablePath,
                                cargoProfileName,
                                cargoTargetRoot,
                                outputRoot,
                                expectedOutputNames,
                                shouldPruneUnknownArtifacts,
                                artifacts,
                                cargoJobs,
                                androidSdkCmake,
                                shouldStripReleaseOutputs,
                                debugSymbolsRoot,
                                llvmObjcopy,
                                llvmStrip,
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
                    val combined =
                        errors.drop(1).fold(errors.first()) { acc, e ->
                            acc.addSuppressed(e)
                            acc
                        }
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
            cargoExecutablePath: String,
            cargoProfileName: String,
            cargoTargetRoot: File,
            outputRoot: File,
            expectedOutputNames: Set<String>,
            shouldPruneUnknownArtifacts: Boolean,
            artifacts: List<RustNativeArtifact>,
            cargoJobs: Int,
            androidCmakePath: String?,
            shouldStripReleaseOutputs: Boolean,
            debugSymbolsRoot: File?,
            llvmObjcopy: File,
            llvmStrip: File,
        ) {
            val targetEnv = config.target.replace('-', '_').uppercase()
            val ccTargetKey = "CC_${config.target.replace('-', '_')}"
            val cxxTargetKey = "CXX_${config.target.replace('-', '_')}"
            val arTargetKey = "AR_${config.target.replace('-', '_')}"
            val abiCargoTargetDir = cargoTargetRoot.resolve(config.abi)
            val abiOutputDir = outputRoot.resolve(config.abi)
            abiOutputDir.mkdirs()
            val abiDebugSymbolsDir = debugSymbolsRoot?.resolve(config.abi)
            abiDebugSymbolsDir?.mkdirs()
            if (shouldPruneUnknownArtifacts) {
                pruneStaleArtifactOutputs(abiOutputDir, expectedOutputNames)
                abiDebugSymbolsDir?.let { symbolsDir ->
                    pruneStaleArtifactOutputs(
                        symbolsDir,
                        expectedOutputNames.mapTo(mutableSetOf()) { "$it.dbg" },
                    )
                }
            }

            // Delete stale BoringSSL cmake build directories whose cached cmake binary,
            // C compiler, or CXX compiler no longer matches the current toolchain.  When
            // cmake detects changed variables it forces a re-configure, and on macOS the
            // re-configure injects `-arch arm64 -isysroot MacOSX.sdk` before the Android
            // toolchain file takes effect, breaking the cross-compilation.  A fresh
            // configure (no existing CMakeCache.txt) works correctly because the toolchain
            // file is applied from the very first cmake invocation.
            if (abiCargoTargetDir.isDirectory) {
                abiCargoTargetDir
                    .walkTopDown()
                    .filter { it.name == "CMakeCache.txt" }
                    .forEach { cacheFile ->
                        val lines = cacheFile.readLines()

                        fun cachedValue(vararg keys: String): String? =
                            lines
                                .firstOrNull { line -> keys.any { line.startsWith("$it=") || line.startsWith("$it:") } }
                                ?.substringAfter("=")
                        val cachedCmake = cachedValue("CMAKE_COMMAND:INTERNAL")
                        val cachedCc =
                            cachedValue(
                                "CMAKE_C_COMPILER:FILEPATH",
                                "CMAKE_C_COMPILER:STRING",
                            )
                        val cachedCxx =
                            cachedValue(
                                "CMAKE_CXX_COMPILER:FILEPATH",
                                "CMAKE_CXX_COMPILER:STRING",
                            )
                        val stale =
                            (
                                androidCmakePath != null && cachedCmake != null &&
                                    cachedCmake != androidCmakePath
                            ) ||
                                (cachedCc != null && cachedCc != config.linker.absolutePath) ||
                                (cachedCxx != null && cachedCxx != config.cxx.absolutePath)
                        if (stale) {
                            // Delete the entire Cargo build-script run directory. The CMake cache
                            // lives under <run>/out/build; deleting only OUT_DIR leaves Cargo's
                            // build-script output/fingerprint intact and the next link can fail on
                            // missing archives. Removing <run> invalidates Cargo's own fingerprint
                            // without making boring-sys watch its mutable generated output.
                            val cargoBuildScriptDir = cacheFile.parentFile.parentFile.parentFile
                            if (!cargoBuildScriptDir.name.startsWith("boring-sys-")) {
                                return@forEach
                            }
                            logger.warn(
                                "Deleting stale BoringSSL Cargo build-script directory " +
                                    "(cmake=$cachedCmake, cc=$cachedCc, cxx=$cachedCxx): " +
                                    cargoBuildScriptDir.absolutePath,
                            )
                            fileSystemOperations.delete { delete(cargoBuildScriptDir) }
                        }
                    }
            }

            val ndkHome = File(sdkDir.get()).resolve("ndk").resolve(ndkVersion.get())
            val appleHostEnvKeys =
                listOf(
                    "SDKROOT",
                    "MACOSX_DEPLOYMENT_TARGET",
                    "IPHONEOS_DEPLOYMENT_TARGET",
                    "TVOS_DEPLOYMENT_TARGET",
                    "WATCHOS_DEPLOYMENT_TARGET",
                    "XROS_DEPLOYMENT_TARGET",
                    "ARCHFLAGS",
                    "RC_ARCHS",
                    "CMAKE_OSX_ARCHITECTURES",
                    "CMAKE_OSX_SYSROOT",
                    "CFLAGS",
                    "CXXFLAGS",
                    "CPPFLAGS",
                    "LDFLAGS",
                )

            val cargoEnvironment =
                buildMap {
                    put("ANDROID_NDK_HOME", ndkHome.absolutePath)
                    put("CC_$targetEnv", config.linker.absolutePath)
                    put(ccTargetKey, config.linker.absolutePath)
                    put("CXX_$targetEnv", config.cxx.absolutePath)
                    put(cxxTargetKey, config.cxx.absolutePath)
                    put("AR_$targetEnv", config.ar.absolutePath)
                    put(arTargetKey, config.ar.absolutePath)
                    put("CARGO_TARGET_${targetEnv}_LINKER", config.linker.absolutePath)
                    put("CARGO_TARGET_${targetEnv}_AR", config.ar.absolutePath)
                    put("CARGO_TARGET_DIR", abiCargoTargetDir.absolutePath)
                    // The cmake crate (used by boring-sys) reads CMAKE to select the cmake
                    // binary.  The Android SDK cmake lacks macOS platform defaults, preventing
                    // `-isysroot MacOSX.sdk` from being injected when cross-compiling for Android.
                    put("CMAKE", requireNotNull(androidCmakePath))
                    // Use static C++ STL so libripdpi.so (and siblings) do not depend on
                    // libc++_shared.so at runtime.  With c++_shared the APK would need to
                    // package libc++_shared.so separately; c++_static embeds the runtime into
                    // each .so instead, which is safe because each library is independently
                    // loaded and they do not share C++ STL state across JNI boundaries.
                    put("BORING_BSSL_RUST_CPPLIB", "c++_static")
                }

            val cargoCommand =
                buildList {
                    add(cargoExecutablePath)
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

            execOperations
                .exec {
                    workingDir = manifest.parentFile
                    environment(cargoEnvironment)
                    // Prevent CMake-based Rust dependencies from inheriting macOS host SDK/arch
                    // settings while cross-compiling for Android.  These must be removed instead
                    // of blanked out, otherwise host rustc rejects empty deployment targets.
                    appleHostEnvKeys.forEach(environment::remove)
                    commandLine(cargoCommand)
                }.assertNormalExitValue()

            for (artifact in artifacts) {
                val builtLibrary =
                    abiCargoTargetDir.resolve(
                        "${config.target}/$cargoProfileName/${artifact.sourceName}",
                    )
                if (!builtLibrary.isFile) {
                    throw GradleException(
                        "Expected native library was not produced: ${builtLibrary.absolutePath}",
                    )
                }

                val packagedLibrary = abiOutputDir.resolve(artifact.outputName)
                copyIfChanged(builtLibrary, packagedLibrary)
                if (shouldStripReleaseOutputs) {
                    val debugSidecar = abiDebugSymbolsDir?.resolve("${artifact.outputName}.dbg")
                    if (debugSidecar != null) {
                        execOperations
                            .exec {
                                commandLine(
                                    llvmObjcopy.absolutePath,
                                    "--only-keep-debug",
                                    builtLibrary.absolutePath,
                                    debugSidecar.absolutePath,
                                )
                            }.assertNormalExitValue()
                    }
                    execOperations
                        .exec {
                            commandLine(
                                llvmStrip.absolutePath,
                                "--strip-all",
                                packagedLibrary.absolutePath,
                            )
                        }.assertNormalExitValue()
                    execOperations
                        .exec {
                            commandLine(
                                llvmObjcopy.absolutePath,
                                "--remove-section=.debug_gdb_scripts",
                                packagedLibrary.absolutePath,
                            )
                        }.assertNormalExitValue()
                    if (debugSidecar != null) {
                        execOperations
                            .exec {
                                commandLine(
                                    llvmObjcopy.absolutePath,
                                    "--add-gnu-debuglink=${debugSidecar.absolutePath}",
                                    packagedLibrary.absolutePath,
                                )
                            }.assertNormalExitValue()
                    }
                }
            }
        }

        private data class AbiConfig(
            val abi: String,
            val target: String,
            val linker: File,
            val cxx: File,
            val ar: File,
        )

        private fun resolveNdkToolchainBinDir(): File {
            val ndkDir = File(sdkDir.get()).resolve("ndk").resolve(ndkVersion.get())
            val toolchainsDir = ndkDir.resolve("toolchains/llvm/prebuilt")
            val hostTag =
                listOf("linux-aarch64", "linux-x86_64", "darwin-arm64", "darwin-x86_64")
                    .firstOrNull { toolchainsDir.resolve(it).isDirectory }
                    ?: throw GradleException("Unsupported NDK host toolchain layout in ${toolchainsDir.absolutePath}")
            return toolchainsDir.resolve(hostTag).resolve("bin")
        }

        private fun installedRustTargets(rustupExecutablePath: String): Set<String> {
            val stdout = ByteArrayOutputStream()
            execOperations
                .exec {
                    standardOutput = stdout
                    commandLine(rustupExecutablePath, "target", "list", "--installed")
                }.assertNormalExitValue()

            return stdout
                .toString()
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

        private fun pruneStaleArtifactOutputs(
            abiOutputDir: File,
            expectedOutputNames: Set<String>,
        ) {
            if (!abiOutputDir.isDirectory) {
                return
            }

            val stale =
                abiOutputDir
                    .listFiles()
                    ?.filter { candidate -> candidate.isFile && candidate.name !in expectedOutputNames }
                    .orEmpty()
            if (stale.isEmpty()) {
                return
            }

            fileSystemOperations.delete {
                delete(stale)
            }
        }

        private fun copyIfChanged(
            source: File,
            target: File,
        ) {
            target.parentFile.mkdirs()
            if (target.isFile && Files.mismatch(source.toPath(), target.toPath()) == -1L) {
                return
            }

            source.copyTo(target, overwrite = true)
        }

        private fun copyFromPrebuilt(prebuiltDir: File) {
            if (!prebuiltDir.isDirectory) {
                throw GradleException(
                    "Prebuilt source directory does not exist: ${prebuiltDir.absolutePath}",
                )
            }
            val outputRoot = outputDir.get().asFile
            val artifacts = artifactSpecs.get().map(::parseArtifactSpec)
            val expectedNames = artifacts.map(RustNativeArtifact::outputName).toSet()

            pruneStaleAbiOutputs(outputRoot)

            for (abi in abis.get()) {
                val abiOutputDir = outputRoot.resolve(abi).also { it.mkdirs() }
                if (pruneUnknownArtifacts.get()) {
                    pruneStaleArtifactOutputs(abiOutputDir, expectedNames)
                }
                for (artifact in artifacts) {
                    val source = prebuiltDir.resolve(abi).resolve(artifact.outputName)
                    if (!source.isFile) {
                        throw GradleException(
                            "Prebuilt artifact missing: ${source.absolutePath}",
                        )
                    }
                    copyIfChanged(source, abiOutputDir.resolve(artifact.outputName))
                }
            }
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

        private fun rejectAmbientCargoOverrides(keys: List<String>) {
            if (keys.isEmpty()) {
                return
            }
            throw GradleException(
                "Android native builds reject output-affecting ambient Cargo overrides: " +
                    keys.sorted().joinToString(", ") +
                    ". Use a dedicated Cargo invocation for custom compiler/profile flags. " +
                    "RUSTC_WRAPPER remains supported.",
            )
        }

        private fun resolvePinnedAndroidSdkCmake(): File {
            val version = cmakeVersion.get()
            val executable = File(sdkDir.get()).resolve("cmake/$version/bin/cmake")
            if (!executable.isFile) {
                throw GradleException(
                    "Pinned Android SDK CMake $version is missing: ${executable.absolutePath}. " +
                        "Install it with `android sdk install cmake/$version`.",
                )
            }
            return executable
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
    }

@CacheableTask
abstract class BuildPluggableTransportAssetsTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val rustSources: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sourcesManifest: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val rustWorkspaceManifest: RegularFileProperty

        @get:Input
        abstract val gitExecutable: Property<String>

        @get:Input
        abstract val goExecutable: Property<String>

        @get:Input
        abstract val cargoExecutable: Property<String>

        @get:Input
        abstract val sdkDir: Property<String>

        @get:Input
        abstract val ndkVersion: Property<String>

        @get:Input
        abstract val cmakeVersion: Property<String>

        @get:Input
        abstract val minSdk: Property<Int>

        @get:Input
        abstract val cargoProfile: Property<String>

        @get:Input
        abstract val buildMode: Property<String>

        @get:Input
        abstract val strictFailures: Property<Boolean>

        @get:Input
        abstract val abis: ListProperty<String>

        @get:Input
        abstract val forbiddenAmbientCargoOverrideKeys: ListProperty<String>

        // CI consumers receive this directory only from the dedicated PT producer.
        // The manifest digest is a separate input so an artifact from another run
        // cannot be substituted silently after download.
        @get:InputDirectory
        @get:Optional
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val prebuiltSourceDir: DirectoryProperty

        @get:Input
        @get:Optional
        abstract val prebuiltManifestSha256: Property<String>

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @get:LocalState
        abstract val workDir: DirectoryProperty

        @TaskAction
        fun build() {
            val mode = buildMode.get()
            val outputRoot = outputDir.get().asFile
            val workRoot = workDir.get().asFile
            val sources = parseSourcesManifest(sourcesManifest.get().asFile)
            val prebuiltPath = prebuiltSourceDir.orNull?.asFile
            if (prebuiltPath != null) {
                copyFromVerifiedPrebuilt(
                    prebuiltRoot = prebuiltPath,
                    expectedManifestSha256 = prebuiltManifestSha256.orNull,
                    sources = sources,
                )
                return
            }
            val strictMode = strictFailures.get()
            val hasGoSources = sources.any { it.sourceType == "go" }
            val hasRustSources = sources.any { it.sourceType == "rust" }
            val buildFromSource =
                when (mode) {
                    "source" -> {
                        true
                    }

                    "stub" -> {
                        false
                    }

                    "auto" -> {
                        (!hasGoSources || executableAvailable(gitExecutable.get(), "--version")) &&
                            (!hasGoSources || executableAvailable(goExecutable.get(), "version")) &&
                            (!hasRustSources || executableAvailable(cargoExecutable.get(), "--version"))
                    }

                    else -> {
                        throw GradleException("Unsupported pluggable transport build mode: $mode")
                    }
                }

            if (buildFromSource && hasRustSources) {
                rejectAmbientCargoOverrides(forbiddenAmbientCargoOverrideKeys.get())
            }

            if (mode == "source" && hasGoSources && !executableAvailable(gitExecutable.get(), "--version")) {
                throw GradleException("git is required for ripdpi.pluggableTransportAssetsMode=source")
            }
            if (mode == "source" && hasGoSources && !executableAvailable(goExecutable.get(), "version")) {
                throw GradleException("go is required for ripdpi.pluggableTransportAssetsMode=source")
            }
            if (mode == "source" && hasRustSources && !executableAvailable(cargoExecutable.get(), "--version")) {
                throw GradleException("cargo is required for ripdpi.pluggableTransportAssetsMode=source")
            }

            try {
                pruneStaleOutputs(outputRoot)
                val records = mutableListOf<Map<String, Any?>>()
                val reposDir = workRoot.resolve("repos")
                val cacheDir = workRoot.resolve("gocache")
                val modCacheDir = workRoot.resolve("gomodcache")
                reposDir.mkdirs()
                cacheDir.mkdirs()
                modCacheDir.mkdirs()

                for (abi in abis.get()) {
                    val abiBinDir = outputRoot.resolve("bin").resolve(abi).apply { mkdirs() }
                    for (source in sources) {
                        var buildError: String? = null
                        val sourceBuildResult =
                            if (buildFromSource) {
                                runCatching {
                                    when (source.sourceType) {
                                        "go" -> {
                                            val repoDir = syncPinnedRepo(reposDir, source)
                                            buildGoBinary(
                                                source = source,
                                                abi = abi,
                                                repoDir = repoDir,
                                                cacheDir = cacheDir.resolve(abi).resolve(source.id),
                                                modCacheDir = modCacheDir,
                                            )
                                        }

                                        "rust" -> {
                                            buildRustBinary(
                                                source = source,
                                                abi = abi,
                                                targetDir =
                                                    workRoot
                                                        .resolve(
                                                            "cargo-target",
                                                        ).resolve(abi)
                                                        .resolve(source.id),
                                            )
                                        }

                                        else -> {
                                            throw GradleException(
                                                "Unsupported pluggable transport source type: ${source.sourceType}",
                                            )
                                        }
                                    }
                                }.getOrElse { error ->
                                    if (strictMode) {
                                        throw error
                                    }
                                    buildError = error.message ?: error::class.java.simpleName
                                    logger.warn(
                                        "Pluggable transport source build failed for ${source.id} ($abi): $buildError",
                                    )
                                    null
                                }
                            } else {
                                null
                            }
                        val capturedError = buildError
                        val packagedUpstreamBinary =
                            sourceBuildResult?.let { builtBinary ->
                                val upstreamName =
                                    if (source.outputNames.size > 1) {
                                        "${source.sourceBinaryName}.upstream"
                                    } else {
                                        "${source.outputNames.single()}.upstream"
                                    }
                                val upstreamBinary = abiBinDir.resolve(upstreamName)
                                copyIfChanged(builtBinary, upstreamBinary)
                                if (source.sourceType == "rust" && cargoProfile.get() == "android-jni") {
                                    val hostBinDir = resolveNdkToolchainBinDir()
                                    val llvmStrip = hostBinDir.resolve("llvm-strip")
                                    val llvmObjcopy = hostBinDir.resolve("llvm-objcopy")
                                    require(llvmStrip.isFile) {
                                        "Android llvm-strip not found for pluggable transport build: ${llvmStrip.absolutePath}"
                                    }
                                    require(llvmObjcopy.isFile) {
                                        "Android llvm-objcopy not found for pluggable transport build: ${llvmObjcopy.absolutePath}"
                                    }
                                    execOperations
                                        .exec {
                                            commandLine(
                                                llvmStrip.absolutePath,
                                                "--strip-all",
                                                upstreamBinary.absolutePath,
                                            )
                                        }.assertNormalExitValue()
                                    execOperations
                                        .exec {
                                            commandLine(
                                                llvmObjcopy.absolutePath,
                                                "--remove-section=.debug_gdb_scripts",
                                                upstreamBinary.absolutePath,
                                            )
                                        }.assertNormalExitValue()
                                }
                                upstreamBinary.setExecutable(true, true)
                                upstreamBinary
                            }
                        for (outputName in source.outputNames) {
                            val launcher = abiBinDir.resolve(outputName)
                            if (packagedUpstreamBinary != null) {
                                launcher.writeText(
                                    sourceBuildLauncherScript("$outputName.upstream"),
                                    Charsets.UTF_8,
                                )
                            } else if (capturedError != null) {
                                launcher.writeText(
                                    sourceBuildFailedScript(outputName, source, capturedError),
                                    Charsets.UTF_8,
                                )
                            } else {
                                launcher.writeText(sourceBuildUnavailableScript(outputName, source), Charsets.UTF_8)
                            }
                            launcher.setExecutable(true, true)
                            records +=
                                mapOf(
                                    "abi" to abi,
                                    "outputName" to outputName,
                                    "sourceId" to source.id,
                                    "sourceType" to source.sourceType,
                                    "repoUrl" to source.repoUrl,
                                    "commit" to source.commit,
                                    "goToolchain" to source.goToolchain,
                                    "cgoEnabled" to source.cgoEnabled,
                                    "packagePath" to source.packagePath,
                                    "packageName" to source.packageName,
                                    "mode" to if (sourceBuildResult != null) "source" else "stub",
                                    "buildError" to buildError,
                                    "launcherSha256" to sha256(launcher),
                                    "upstreamBinary" to packagedUpstreamBinary?.name,
                                    "upstreamSha256" to packagedUpstreamBinary?.let(::sha256),
                                )
                        }
                    }
                }

                val manifestFile = outputRoot.resolve("metadata/pluggable-transports.json")
                manifestFile.parentFile.mkdirs()
                manifestFile.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            mapOf(
                                "schemaVersion" to 1,
                                "buildMode" to if (buildFromSource) "source" else "stub",
                                "strictFailures" to strictMode,
                                "sources" to
                                    sources.map { source ->
                                        mapOf(
                                            "id" to source.id,
                                            "sourceType" to source.sourceType,
                                            "repoUrl" to source.repoUrl,
                                            "commit" to source.commit,
                                            "goToolchain" to source.goToolchain,
                                            "cgoEnabled" to source.cgoEnabled,
                                            "packagePath" to source.packagePath,
                                            "packageName" to source.packageName,
                                            "sourceBinaryName" to source.sourceBinaryName,
                                            "outputNames" to source.outputNames,
                                        )
                                    },
                                "artifacts" to records,
                            ),
                        ),
                    ),
                    Charsets.UTF_8,
                )
            } finally {
                makeTreeWritable(workRoot)
            }
        }

        private fun parseSourcesManifest(file: File): List<PluggableTransportSource> {
            val root = JsonSlurper().parse(file) as Map<*, *>
            val schemaVersion = (root["schemaVersion"] as Number?)?.toInt()
            require(schemaVersion == 1) { "Unsupported pluggable transport source schema version: $schemaVersion" }
            val sources = root["sources"] as? List<*> ?: error("pluggable transport source manifest missing sources")
            return sources.map { entry ->
                val data = entry as Map<*, *>
                val sourceType = data["sourceType"]?.toString()?.takeIf(String::isNotBlank) ?: "go"
                PluggableTransportSource(
                    id = data.requireString("id"),
                    sourceType = sourceType,
                    repoUrl = data["repoUrl"]?.toString()?.takeIf(String::isNotBlank),
                    commit = data["commit"]?.toString()?.takeIf(String::isNotBlank),
                    goToolchain = data["goToolchain"]?.toString()?.takeIf(String::isNotBlank),
                    cgoEnabled = data["cgoEnabled"]?.toString()?.toBooleanStrictOrNull() ?: false,
                    packagePath = data["packagePath"]?.toString()?.takeIf(String::isNotBlank),
                    packageName = data["packageName"]?.toString()?.takeIf(String::isNotBlank),
                    sourceBinaryName = data.requireString("sourceBinaryName"),
                    outputNames = (data["outputNames"] as? List<*>)?.map { it.toString() }.orEmpty(),
                ).also { source ->
                    require(source.sourceType == "go" || source.sourceType == "rust") {
                        "Source ${source.id} has unsupported sourceType=${source.sourceType}"
                    }
                    if (source.sourceType == "go") {
                        require(!source.repoUrl.isNullOrBlank()) { "Go source ${source.id} must declare repoUrl" }
                        require(!source.commit.isNullOrBlank()) { "Go source ${source.id} must declare commit" }
                        require(
                            !source.packagePath.isNullOrBlank(),
                        ) { "Go source ${source.id} must declare packagePath" }
                    }
                    if (source.sourceType == "rust") {
                        require(
                            !source.packageName.isNullOrBlank(),
                        ) { "Rust source ${source.id} must declare packageName" }
                    }
                    require(
                        source.outputNames.isNotEmpty(),
                    ) { "Source ${source.id} must declare at least one output name" }
                }
            }
        }

        private fun copyFromVerifiedPrebuilt(
            prebuiltRoot: File,
            expectedManifestSha256: String?,
            sources: List<PluggableTransportSource>,
        ) {
            require(prebuiltRoot.isDirectory) {
                "Prebuilt pluggable transport assets directory is missing: ${prebuiltRoot.absolutePath}"
            }
            require(expectedManifestSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
                "ripdpi.prebuiltPluggableTransportAssetsManifestSha256 must be a lowercase SHA-256 digest"
            }

            val manifestFile = prebuiltRoot.resolve("metadata/pluggable-transports.json")
            require(manifestFile.isFile) {
                "Prebuilt pluggable transport assets are missing metadata/pluggable-transports.json"
            }
            require(sha256(manifestFile) == expectedManifestSha256) {
                "Prebuilt pluggable transport manifest digest does not match the producer output"
            }

            val manifest =
                JsonSlurper().parse(manifestFile) as? Map<*, *>
                    ?: throw GradleException("Prebuilt pluggable transport manifest must be a JSON object")
            require((manifest["schemaVersion"] as? Number)?.toInt() == 1) {
                "Unsupported prebuilt pluggable transport manifest schema"
            }
            require(manifest["buildMode"] == "source") {
                "Prebuilt pluggable transport manifest must contain source-built artifacts"
            }
            verifyPrebuiltSources(manifest, sources)

            val records =
                manifest["artifacts"] as? List<*>
                    ?: throw GradleException("Prebuilt pluggable transport manifest is missing artifacts")
            val parsedRecords =
                records.map { entry ->
                    val record =
                        entry as? Map<*, *>
                            ?: throw GradleException("Prebuilt pluggable transport artifact record must be an object")
                    val abi = record.requireString("abi")
                    val outputName = record.requireString("outputName")
                    (abi to outputName) to record
                }
            val recordsByKey = parsedRecords.toMap()
            require(recordsByKey.size == parsedRecords.size) {
                "Prebuilt pluggable transport manifest contains duplicate ABI/output records"
            }
            val expectedRecords =
                abis
                    .get()
                    .flatMap { abi ->
                        sources.flatMap { source -> source.outputNames.map { outputName -> abi to outputName } }
                    }.toSet()
            require(recordsByKey.keys.containsAll(expectedRecords)) {
                "Prebuilt pluggable transport manifest does not cover the requested ABI/output set"
            }

            for ((abi, outputName) in expectedRecords) {
                val record = requireNotNull(recordsByKey[abi to outputName])
                val source =
                    sources.singleOrNull { outputName in it.outputNames }
                        ?: throw GradleException("No source manifest entry owns prebuilt output $outputName")
                require(record.requireString("sourceId") == source.id) {
                    "Prebuilt pluggable transport artifact $abi/$outputName has an unexpected source"
                }
                require(record["mode"] == "source") {
                    "Prebuilt pluggable transport artifact $abi/$outputName was not source-built"
                }

                val binDir = prebuiltRoot.resolve("bin").resolve(abi)
                val launcher = binDir.resolve(outputName)
                require(launcher.isFile && launcher.canExecute()) {
                    "Prebuilt pluggable transport launcher is missing or not executable: $abi/$outputName"
                }
                require(sha256(launcher) == record.requireString("launcherSha256")) {
                    "Prebuilt pluggable transport launcher digest mismatch: $abi/$outputName"
                }
                val upstreamName = record.requireString("upstreamBinary")
                require(File(upstreamName).name == upstreamName) {
                    "Prebuilt pluggable transport upstream path is unsafe: $abi/$outputName"
                }
                val upstream = binDir.resolve(upstreamName)
                require(upstream.isFile && upstream.canExecute()) {
                    "Prebuilt pluggable transport upstream binary is missing or not executable: $abi/$upstreamName"
                }
                require(sha256(upstream) == record.requireString("upstreamSha256")) {
                    "Prebuilt pluggable transport upstream digest mismatch: $abi/$upstreamName"
                }
            }

            val outputRoot = outputDir.get().asFile
            pruneStaleOutputs(outputRoot)
            copyIfChanged(manifestFile, outputRoot.resolve("metadata/pluggable-transports.json"))
            for ((abi, outputName) in expectedRecords) {
                val record = requireNotNull(recordsByKey[abi to outputName])
                val sourceBinDir = prebuiltRoot.resolve("bin").resolve(abi)
                val targetBinDir = outputRoot.resolve("bin").resolve(abi)
                val launcher = sourceBinDir.resolve(outputName)
                val upstream = sourceBinDir.resolve(record.requireString("upstreamBinary"))
                val targetLauncher = targetBinDir.resolve(launcher.name)
                val targetUpstream = targetBinDir.resolve(upstream.name)
                copyIfChanged(launcher, targetLauncher)
                copyIfChanged(upstream, targetUpstream)
                targetLauncher.setExecutable(true, true)
                targetUpstream.setExecutable(true, true)
            }
            makeTreeWritable(outputRoot)
        }

        private fun verifyPrebuiltSources(
            manifest: Map<*, *>,
            sources: List<PluggableTransportSource>,
        ) {
            val manifestSources =
                manifest["sources"] as? List<*>
                    ?: throw GradleException("Prebuilt pluggable transport manifest is missing sources")
            val byId =
                manifestSources.associate { entry ->
                    val record =
                        entry as? Map<*, *>
                            ?: throw GradleException("Prebuilt pluggable transport source record must be an object")
                    record.requireString("id") to record
                }
            require(
                byId.size == manifestSources.size && byId.keys == sources.map(PluggableTransportSource::id).toSet(),
            ) {
                "Prebuilt pluggable transport manifest has an unexpected source set"
            }
            for (source in sources) {
                val record = requireNotNull(byId[source.id])
                require(record.requireString("sourceType") == source.sourceType) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected source type"
                }
                require(record.optionalString("repoUrl") == source.repoUrl) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected repository"
                }
                require(record.optionalString("commit") == source.commit) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected pinned revision"
                }
                require(record.optionalString("goToolchain") == source.goToolchain) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected Go toolchain"
                }
                require(record.optionalString("packagePath") == source.packagePath) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected package path"
                }
                require(record.optionalString("packageName") == source.packageName) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected Rust package"
                }
                require(record.requireString("sourceBinaryName") == source.sourceBinaryName) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected binary name"
                }
                require(record["cgoEnabled"] == source.cgoEnabled) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected CGO setting"
                }
                require((record["outputNames"] as? List<*>)?.map(Any?::toString) == source.outputNames) {
                    "Prebuilt pluggable transport source ${source.id} has an unexpected output list"
                }
            }
        }

        private fun syncPinnedRepo(
            reposDir: File,
            source: PluggableTransportSource,
        ): File {
            val repoUrl = requireNotNull(source.repoUrl) { "Go source ${source.id} is missing repoUrl" }
            val commit = requireNotNull(source.commit) { "Go source ${source.id} is missing commit" }
            val repoDir = reposDir.resolve(source.id)
            if (!repoDir.resolve(".git").exists()) {
                fileSystemOperations.delete { delete(repoDir) }
                execWithRetry("Clone ${source.id} pluggable transport source") {
                    commandLine(
                        gitExecutable.get(),
                        "clone",
                        "--filter=blob:none",
                        repoUrl,
                        repoDir.absolutePath,
                    )
                }
            }
            execWithRetry("Fetch ${source.id} pluggable transport source") {
                workingDir = repoDir
                commandLine(gitExecutable.get(), "fetch", "--depth", "1", "origin", commit)
            }
            execOperations
                .exec {
                    workingDir = repoDir
                    commandLine(gitExecutable.get(), "checkout", "--force", commit)
                }.assertNormalExitValue()
            execOperations
                .exec {
                    workingDir = repoDir
                    commandLine(gitExecutable.get(), "clean", "-fdx")
                }.assertNormalExitValue()
            return repoDir
        }

        private fun execWithRetry(
            description: String,
            attempts: Int = 3,
            configure: ExecSpec.() -> Unit,
        ) {
            var lastExitValue: Int? = null
            var lastError: RuntimeException? = null
            repeat(attempts) { attemptIndex ->
                try {
                    val result =
                        execOperations.exec {
                            isIgnoreExitValue = true
                            configure()
                        }
                    if (result.exitValue == 0) {
                        return
                    }
                    lastExitValue = result.exitValue
                    logger.warn(
                        "$description failed with exit ${result.exitValue} (attempt ${attemptIndex + 1}/$attempts)",
                    )
                } catch (error: RuntimeException) {
                    lastError = error
                    logger.warn(
                        "$description failed with ${error::class.java.simpleName} (attempt ${attemptIndex + 1}/$attempts): ${error.message}",
                    )
                }
                if (attemptIndex < attempts - 1) {
                    Thread.sleep(5_000L * (attemptIndex + 1))
                }
            }
            val failureMessage =
                if (lastExitValue != null) {
                    "$description failed after $attempts attempts with exit value $lastExitValue"
                } else {
                    "$description failed after $attempts attempts"
                }
            throw GradleException(failureMessage, lastError)
        }

        private fun buildGoBinary(
            source: PluggableTransportSource,
            abi: String,
            repoDir: File,
            cacheDir: File,
            modCacheDir: File,
        ): File {
            val packagePath = requireNotNull(source.packagePath) { "Go source ${source.id} is missing packagePath" }
            val outputFile = workDir.get().asFile.resolve("built/$abi/${source.id}/${source.sourceBinaryName}")
            outputFile.parentFile.mkdirs()
            cacheDir.mkdirs()
            val goArch = abiToGoArch(abi)
            // Go requires the external Android linker for android/arm source builds.
            val requiresExternalAndroidLinker = abi == "armeabi-v7a"
            val enableCgo = source.cgoEnabled || requiresExternalAndroidLinker
            val cgoEnvironment = buildGoCgoEnvironment(enableCgo, abi)
            execOperations
                .exec {
                    workingDir = repoDir
                    environment("GOOS", "android")
                    environment("GOARCH", goArch.arch)
                    goArch.goarm?.let { environment("GOARM", it) }
                    cgoEnvironment.forEach(::environment)
                    if (!enableCgo) {
                        environment("CGO_ENABLED", "0")
                    }
                    environment("GOCACHE", cacheDir.absolutePath)
                    environment("GOMODCACHE", modCacheDir.absolutePath)
                    environment("GOFLAGS", "-trimpath -buildvcs=false")
                    source.goToolchain?.let { environment("GOTOOLCHAIN", it) }
                    commandLine(
                        goExecutable.get(),
                        "build",
                        "-trimpath",
                        "-buildvcs=false",
                        "-ldflags=-s -w -buildid=",
                        "-o",
                        outputFile.absolutePath,
                        packagePath,
                    )
                }.assertNormalExitValue()
            if (!outputFile.isFile) {
                throw GradleException(
                    "Expected pluggable transport binary was not produced: ${outputFile.absolutePath}",
                )
            }
            return outputFile
        }

        private fun buildRustBinary(
            source: PluggableTransportSource,
            abi: String,
            targetDir: File,
        ): File {
            val packageName = requireNotNull(source.packageName) { "Rust source ${source.id} is missing packageName" }
            val target = abiToRustTarget(abi)
            val clangTarget = abiToClangTarget(abi)
            val hostBinDir = resolveNdkToolchainBinDir()
            val linker = hostBinDir.resolve("${clangTarget}${minSdk.get()}-clang")
            val cxx = hostBinDir.resolve("${clangTarget}${minSdk.get()}-clang++")
            val ar = hostBinDir.resolve("llvm-ar")
            require(
                linker.isFile,
            ) { "Android linker not found for pluggable transport build ($abi): ${linker.absolutePath}" }
            require(
                cxx.isFile,
            ) { "Android C++ linker not found for pluggable transport build ($abi): ${cxx.absolutePath}" }
            require(ar.isFile) { "Android archiver not found for pluggable transport build ($abi): ${ar.absolutePath}" }

            val targetEnv = target.replace('-', '_').uppercase()
            val targetKey = target.replace('-', '_')
            val ndkHome = File(sdkDir.get()).resolve("ndk").resolve(ndkVersion.get())
            val cargoEnvironment =
                mutableMapOf(
                    "ANDROID_NDK_HOME" to ndkHome.absolutePath,
                    "CC_$targetEnv" to linker.absolutePath,
                    "CC_$targetKey" to linker.absolutePath,
                    "CXX_$targetEnv" to cxx.absolutePath,
                    "CXX_$targetKey" to cxx.absolutePath,
                    "AR_$targetEnv" to ar.absolutePath,
                    "AR_$targetKey" to ar.absolutePath,
                    "CARGO_TARGET_${targetEnv}_LINKER" to linker.absolutePath,
                    "CARGO_TARGET_${targetEnv}_AR" to ar.absolutePath,
                    "CARGO_TARGET_DIR" to targetDir.absolutePath,
                    "BORING_BSSL_RUST_CPPLIB" to "c++_static",
                )
            cargoEnvironment["CMAKE"] = resolvePinnedAndroidSdkCmake().absolutePath

            val manifest = rustWorkspaceManifest.get().asFile
            execWithRetry("Build ${source.id} Rust pluggable transport for $abi") {
                workingDir = manifest.parentFile
                environment(cargoEnvironment)
                appleHostEnvKeys().forEach(environment::remove)
                commandLine(
                    cargoExecutable.get(),
                    "build",
                    "--manifest-path",
                    manifest.absolutePath,
                    "-p",
                    packageName,
                    "--locked",
                    "--target",
                    target,
                    "--profile",
                    cargoProfile.get(),
                )
            }

            val outputFile = targetDir.resolve("$target/${cargoProfile.get()}/${source.sourceBinaryName}")
            if (!outputFile.isFile) {
                throw GradleException(
                    "Expected Rust pluggable transport binary was not produced: ${outputFile.absolutePath}",
                )
            }
            return outputFile
        }

        private fun buildGoCgoEnvironment(
            enableCgo: Boolean,
            abi: String,
        ): Map<String, String> {
            if (!enableCgo) {
                return emptyMap()
            }
            val hostBinDir = resolveNdkToolchainBinDir()
            val clangTarget = abiToClangTarget(abi)
            val apiLevel = minSdk.get()
            val clang = hostBinDir.resolve("${clangTarget}$apiLevel-clang")
            val clangxx = hostBinDir.resolve("${clangTarget}$apiLevel-clang++")
            require(clang.isFile) {
                "Android clang linker not found for pluggable transport build ($abi): ${clang.absolutePath}"
            }
            require(clangxx.isFile) {
                "Android clang++ linker not found for pluggable transport build ($abi): ${clangxx.absolutePath}"
            }
            return mapOf(
                "CGO_ENABLED" to "1",
                "CC" to clang.absolutePath,
                "CXX" to clangxx.absolutePath,
            )
        }

        private fun resolveNdkToolchainBinDir(): File {
            val ndkDir = File(sdkDir.get()).resolve("ndk").resolve(ndkVersion.get())
            val toolchainsDir = ndkDir.resolve("toolchains/llvm/prebuilt")
            val hostTag =
                listOf("linux-aarch64", "linux-x86_64", "darwin-arm64", "darwin-x86_64")
                    .firstOrNull { toolchainsDir.resolve(it).isDirectory }
                    ?: throw GradleException("Unsupported NDK host toolchain layout in ${toolchainsDir.absolutePath}")
            return toolchainsDir.resolve(hostTag).resolve("bin")
        }

        private fun resolvePinnedAndroidSdkCmake(): File {
            val version = cmakeVersion.get()
            val executable = File(sdkDir.get()).resolve("cmake/$version/bin/cmake")
            if (!executable.isFile) {
                throw GradleException(
                    "Pinned Android SDK CMake $version is missing: ${executable.absolutePath}. " +
                        "Install it with `android sdk install cmake/$version`.",
                )
            }
            return executable
        }

        private fun rejectAmbientCargoOverrides(keys: List<String>) {
            if (keys.isEmpty()) {
                return
            }
            throw GradleException(
                "Android native builds reject output-affecting ambient Cargo overrides: " +
                    keys.sorted().joinToString(", ") +
                    ". Use a dedicated Cargo invocation for custom compiler/profile flags. " +
                    "RUSTC_WRAPPER remains supported.",
            )
        }

        private fun appleHostEnvKeys(): List<String> =
            listOf(
                "SDKROOT",
                "MACOSX_DEPLOYMENT_TARGET",
                "IPHONEOS_DEPLOYMENT_TARGET",
                "TVOS_DEPLOYMENT_TARGET",
                "WATCHOS_DEPLOYMENT_TARGET",
                "XROS_DEPLOYMENT_TARGET",
                "ARCHFLAGS",
                "RC_ARCHS",
                "CMAKE_OSX_ARCHITECTURES",
                "CMAKE_OSX_SYSROOT",
                "CFLAGS",
                "CXXFLAGS",
                "CPPFLAGS",
                "LDFLAGS",
            )

        private fun abiToRustTarget(abi: String): String =
            when (abi) {
                "armeabi-v7a" -> "armv7-linux-androideabi"
                "arm64-v8a" -> "aarch64-linux-android"
                "x86" -> "i686-linux-android"
                "x86_64" -> "x86_64-linux-android"
                else -> throw GradleException("Unsupported ABI for pluggable transport build: $abi")
            }

        private fun abiToClangTarget(abi: String): String =
            when (abi) {
                "armeabi-v7a" -> "armv7a-linux-androideabi"
                "arm64-v8a" -> "aarch64-linux-android"
                "x86" -> "i686-linux-android"
                "x86_64" -> "x86_64-linux-android"
                else -> throw GradleException("Unsupported ABI for pluggable transport build: $abi")
            }

        private fun executableAvailable(
            executable: String,
            vararg args: String,
        ): Boolean =
            runCatching {
                execOperations.exec {
                    isIgnoreExitValue = true
                    commandLine(listOf(executable) + args)
                }
            }.getOrNull()?.exitValue == 0

        private fun pruneStaleOutputs(outputRoot: File) {
            if (!outputRoot.isDirectory) {
                return
            }
            fileSystemOperations.delete {
                delete(outputRoot)
            }
        }

        private fun sourceBuildLauncherScript(upstreamBinaryName: String): String =
            """
            |#!/system/bin/sh
            |SELF_DIR="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)"
            |exec "${'$'}SELF_DIR/$upstreamBinaryName" "${'$'}@"
            """.trimMargin()

        private fun sourceBuildFailedScript(
            outputName: String,
            source: PluggableTransportSource,
            buildError: String,
        ): String =
            """
            |#!/system/bin/sh
            |echo "$outputName could not be source-built from ${source.id}${source.commit?.let { "@$it" } ?: ""}." >&2
            |echo "${buildError.replace("\"", "\\\"")}" >&2
            |exit 78
            """.trimMargin()

        private fun sourceBuildUnavailableScript(
            outputName: String,
            source: PluggableTransportSource,
        ): String =
            """
            |#!/system/bin/sh
            |echo "$outputName is unavailable in this APK because pluggable transport source builds were skipped." >&2
            |echo "Rebuild with -Pripdpi.pluggableTransportAssetsMode=source" >&2
            |echo "to compile ${source.id}${source.repoUrl?.let { " from $it" } ?: ""}." >&2
            |exit 78
            """.trimMargin()

        private fun makeTreeWritable(root: File) {
            if (!root.exists()) {
                return
            }
            root.walkBottomUp().forEach { file ->
                file.setReadable(true, false)
                file.setWritable(true, false)
                if (file.isDirectory) {
                    file.setExecutable(true, false)
                }
            }
        }

        private fun copyIfChanged(
            source: File,
            target: File,
        ) {
            target.parentFile.mkdirs()
            if (target.isFile && Files.mismatch(source.toPath(), target.toPath()) == -1L) {
                return
            }
            source.copyTo(target, overwrite = true)
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun abiToGoArch(abi: String): GoArch =
            when (abi) {
                "armeabi-v7a" -> GoArch(arch = "arm", goarm = "7")
                "arm64-v8a" -> GoArch(arch = "arm64")
                "x86" -> GoArch(arch = "386")
                "x86_64" -> GoArch(arch = "amd64")
                else -> throw GradleException("Unsupported ABI for pluggable transport build: $abi")
            }

        private data class GoArch(
            val arch: String,
            val goarm: String? = null,
        )

        private fun Map<*, *>.requireString(key: String): String =
            this[key]?.toString()?.takeIf(String::isNotBlank)
                ?: throw GradleException("Missing required pluggable transport manifest field: $key")

        private fun Map<*, *>.optionalString(key: String): String? = this[key]?.toString()?.takeIf(String::isNotBlank)
    }

val rustNativeAbis = resolvedNativeAbis()
val rustNativeCargoProfile = resolvedNativeCargoProfile()
val rustNativeAbiParallelism =
    providers
        .gradleProperty("ripdpi.nativeAbiParallelism")
        .map(String::toInt)
        .orElse(Int.MAX_VALUE)
val rustNativeCpuBudget =
    providers
        .gradleProperty("ripdpi.nativeCpuBudget")
        .map(String::toInt)
val detectedAmbientCargoOverrideKeys =
    buildSet {
        listOf(
            "RUSTFLAGS",
            "CARGO_ENCODED_RUSTFLAGS",
            "RUSTC",
            "RUSTC_WORKSPACE_WRAPPER",
        ).filterTo(this) { providers.environmentVariable(it).isPresent }
        for (prefix in listOf("CARGO_PROFILE_", "CARGO_BUILD_", "CARGO_TARGET_")) {
            addAll(
                providers
                    .environmentVariablesPrefixedBy(prefix)
                    .get()
                    .keys
                    .filterNot { it == "CARGO_TARGET_DIR" },
            )
        }
    }.sorted()
val pluggableTransportAssetsMode = resolvedPluggableTransportAssetsMode()
val pluggableTransportAssetsStrictFailures = resolvedPluggableTransportAssetsStrictFailures()
val rustNativeArtifactSpecs =
    listOf(
        "ripdpi-android|libripdpi_android.so|libripdpi.so",
        "ripdpi-relay-android|libripdpi_relay_android.so|libripdpi-relay.so",
        "ripdpi-warp-android|libripdpi_warp_android.so|libripdpi-warp.so",
        "ripdpi-amneziawg-android|libripdpi_amneziawg_android.so|libripdpi-amneziawg.so",
        "ripdpi-tunnel-android|libripdpi_tunnel_android.so|libripdpi-tunnel.so",
    )
val rustWorkspaceManifestFile =
    rootProject.layout.projectDirectory
        .file("native/rust/Cargo.toml")
        .asFile
val rustWorkspaceDir = rustWorkspaceManifestFile.parentFile
val rustWorkspaceCratesDir = rustWorkspaceDir.resolve("crates")

// Track every workspace crate source; Cargo still decides the minimal dependency graph.
fun rustWorkspaceCrateSources() =
    fileTree(rustWorkspaceCratesDir) {
        include("**/*")
        exclude("**/target/**")
        exclude("**/.git/**")
    }
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val generatedNativeSymbolsDir = layout.buildDirectory.dir("generated/nativeSymbols")
val generatedRootHelperAssetsDir = layout.buildDirectory.dir("generated/rootHelperAssets")
val generatedNaiveProxyAssetsDir = layout.buildDirectory.dir("generated/naiveProxyAssets")
val generatedCloudflareOriginAssetsDir = layout.buildDirectory.dir("generated/cloudflareOriginAssets")
val generatedPtAssetsDir = layout.buildDirectory.dir("generated/pluggableTransportAssets")
// All native Cargo tasks share this local state. They build different output
// directories, but a shard's packages have a large common Rust/C/BoringSSL
// graph; keeping one target root per ABI lets Cargo reuse it across tasks.
val rustNativeLibsBuildDir = layout.buildDirectory.dir("intermediates/rust-native-libs")
val ptAssetsBuildDir = layout.buildDirectory.dir("intermediates/pluggable-transport-assets")
val ptSourcesManifestFile =
    rootProject.layout.projectDirectory
        .file("native/pluggable-transports/sources.json")
        .asFile
val rustRootHelperArtifactSpecs =
    listOf(
        "ripdpi-root-helper|ripdpi-root-helper|ripdpi-root-helper",
    )
val rustNaiveProxyArtifactSpecs =
    listOf(
        "ripdpi-naiveproxy|ripdpi-naiveproxy|ripdpi-naiveproxy",
    )
val rustCloudflareOriginArtifactSpecs =
    listOf(
        "ripdpi-cloudflare-origin|ripdpi-cloudflare-origin|ripdpi-cloudflare-origin",
    )

extensions.configure<LibraryExtension> {
    sourceSets["main"].jniLibs.directories.add(generatedJniLibsDir.get().asFile.absolutePath)
    sourceSets["main"].assets.directories.add(generatedRootHelperAssetsDir.get().asFile.absolutePath)
    sourceSets["main"].assets.directories.add(generatedNaiveProxyAssetsDir.get().asFile.absolutePath)
    sourceSets["main"].assets.directories.add(generatedCloudflareOriginAssetsDir.get().asFile.absolutePath)
    sourceSets["main"].assets.directories.add(generatedPtAssetsDir.get().asFile.absolutePath)
}

val buildRustNativeLibs =
    tasks.register<BuildRustNativeLibsTask>("buildRustNativeLibs") {
        group = "build"
        description = "Builds Rust native libraries into Gradle-managed jniLibs outputs."

        nativeSources.from(rustWorkspaceManifestFile)
        nativeSources.from(
            listOf("Cargo.lock", "rust-toolchain.toml")
                .map(rustWorkspaceDir::resolve)
                .filter { it.isFile },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve(".cargo")) {
                include("**/*")
            },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve("vendor")) {
                include("**/*")
            },
        )
        nativeSources.from(
            rustWorkspaceCrateSources(),
        )
        workspaceManifest.set(rustWorkspaceManifestFile)
        sdkDir.set(resolveAndroidSdkDir())
        cargoExecutable.set(resolveRustTool("cargo"))
        rustupExecutable.set(resolveRustTool("rustup"))
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cmakeVersion.set(providers.gradleProperty("ripdpi.nativeCmakeVersion"))
        cargoProfile.set(rustNativeCargoProfile)
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        abiParallelism.set(rustNativeAbiParallelism)
        cpuBudget.set(rustNativeCpuBudget)
        forbiddenAmbientCargoOverrideKeys.set(detectedAmbientCargoOverrideKeys)
        artifactSpecs.set(rustNativeArtifactSpecs)
        stripReleaseOutputs.set(true)
        cargoTargetDir.set(rustNativeLibsBuildDir)
        outputDir.set(generatedJniLibsDir)
        debugSymbolsDir.set(generatedNativeSymbolsDir)
        providers
            .gradleProperty("ripdpi.prebuiltJniLibsDir")
            .orNull
            ?.takeIf(String::isNotBlank)
            ?.let { prebuiltSourceDir.set(file(it)) }
    }

val buildRustRootHelper =
    tasks.register<BuildRustNativeLibsTask>("buildRustRootHelper") {
        group = "build"
        description = "Builds the Rust root helper binary into generated assets."

        nativeSources.from(rustWorkspaceManifestFile)
        nativeSources.from(
            listOf("Cargo.lock", "rust-toolchain.toml")
                .map(rustWorkspaceDir::resolve)
                .filter { it.isFile },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve(".cargo")) {
                include("**/*")
            },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve("vendor")) {
                include("**/*")
            },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve("crates/ripdpi-root-helper")) {
                exclude("**/target/**")
            },
        )
        nativeSources.from(
            rustWorkspaceCrateSources(),
        )
        workspaceManifest.set(rustWorkspaceManifestFile)
        sdkDir.set(resolveAndroidSdkDir())
        cargoExecutable.set(resolveRustTool("cargo"))
        rustupExecutable.set(resolveRustTool("rustup"))
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cmakeVersion.set(providers.gradleProperty("ripdpi.nativeCmakeVersion"))
        cargoProfile.set(rustNativeCargoProfile)
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        abiParallelism.set(rustNativeAbiParallelism)
        cpuBudget.set(rustNativeCpuBudget)
        forbiddenAmbientCargoOverrideKeys.set(detectedAmbientCargoOverrideKeys)
        artifactSpecs.set(rustRootHelperArtifactSpecs)
        stripReleaseOutputs.set(true)
        pruneUnknownArtifacts.set(false)
        cargoTargetDir.set(rustNativeLibsBuildDir)
        // Output to assets/bin/<abi>/ so Kotlin can extract at runtime.
        outputDir.set(generatedRootHelperAssetsDir.map { it.dir("bin") })
        providers
            .gradleProperty("ripdpi.prebuiltRootHelperDir")
            .orNull
            ?.takeIf(String::isNotBlank)
            ?.let { prebuiltSourceDir.set(file(it)) }
    }

val buildRustNaiveProxy =
    tasks.register<BuildRustNativeLibsTask>("buildRustNaiveProxy") {
        group = "build"
        description = "Builds the NaiveProxy helper binary into generated assets."

        nativeSources.from(rustWorkspaceManifestFile)
        nativeSources.from(
            listOf("Cargo.lock", "rust-toolchain.toml")
                .map(rustWorkspaceDir::resolve)
                .filter { it.isFile },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve(".cargo")) {
                include("**/*")
            },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve("vendor")) {
                include("**/*")
            },
        )
        nativeSources.from(
            rustWorkspaceCrateSources(),
        )
        workspaceManifest.set(rustWorkspaceManifestFile)
        sdkDir.set(resolveAndroidSdkDir())
        cargoExecutable.set(resolveRustTool("cargo"))
        rustupExecutable.set(resolveRustTool("rustup"))
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cmakeVersion.set(providers.gradleProperty("ripdpi.nativeCmakeVersion"))
        cargoProfile.set(rustNativeCargoProfile)
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        abiParallelism.set(rustNativeAbiParallelism)
        cpuBudget.set(rustNativeCpuBudget)
        forbiddenAmbientCargoOverrideKeys.set(detectedAmbientCargoOverrideKeys)
        artifactSpecs.set(rustNaiveProxyArtifactSpecs)
        stripReleaseOutputs.set(true)
        pruneUnknownArtifacts.set(false)
        cargoTargetDir.set(rustNativeLibsBuildDir)
        outputDir.set(generatedNaiveProxyAssetsDir.map { it.dir("bin") })
        providers
            .gradleProperty("ripdpi.prebuiltNaiveProxyDir")
            .orNull
            ?.takeIf(String::isNotBlank)
            ?.let { prebuiltSourceDir.set(file(it)) }
    }

val buildRustCloudflareOrigin =
    tasks.register<BuildRustNativeLibsTask>("buildRustCloudflareOrigin") {
        group = "build"
        description = "Builds the Cloudflare local-origin helper binary into generated assets."

        nativeSources.from(rustWorkspaceManifestFile)
        nativeSources.from(
            listOf("Cargo.lock", "rust-toolchain.toml")
                .map(rustWorkspaceDir::resolve)
                .filter { it.isFile },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve(".cargo")) {
                include("**/*")
            },
        )
        nativeSources.from(
            fileTree(rustWorkspaceDir.resolve("vendor")) {
                include("**/*")
            },
        )
        nativeSources.from(
            rustWorkspaceCrateSources(),
        )
        workspaceManifest.set(rustWorkspaceManifestFile)
        sdkDir.set(resolveAndroidSdkDir())
        cargoExecutable.set(resolveRustTool("cargo"))
        rustupExecutable.set(resolveRustTool("rustup"))
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cmakeVersion.set(providers.gradleProperty("ripdpi.nativeCmakeVersion"))
        cargoProfile.set(rustNativeCargoProfile)
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        abiParallelism.set(rustNativeAbiParallelism)
        cpuBudget.set(rustNativeCpuBudget)
        forbiddenAmbientCargoOverrideKeys.set(detectedAmbientCargoOverrideKeys)
        artifactSpecs.set(rustCloudflareOriginArtifactSpecs)
        stripReleaseOutputs.set(true)
        pruneUnknownArtifacts.set(false)
        cargoTargetDir.set(rustNativeLibsBuildDir)
        outputDir.set(generatedCloudflareOriginAssetsDir.map { it.dir("bin") })
        providers
            .gradleProperty("ripdpi.prebuiltCloudflareOriginDir")
            .orNull
            ?.takeIf(String::isNotBlank)
            ?.let { prebuiltSourceDir.set(file(it)) }
    }

val buildPluggableTransportAssets =
    tasks.register<BuildPluggableTransportAssetsTask>("buildPluggableTransportAssets") {
        group = "build"
        description = "Builds source-pinned pluggable transport helper assets or stub launchers."

        sourcesManifest.set(ptSourcesManifestFile)
        rustWorkspaceManifest.set(rustWorkspaceManifestFile)
        rustSources.from(rustWorkspaceCrateSources())
        rustSources.from(
            fileTree(rustWorkspaceDir.resolve("vendor")) {
                include("**/*")
            },
        )
        rustSources.from(rustWorkspaceDir.resolve("Cargo.lock"))
        rustSources.from(rustWorkspaceDir.resolve("rust-toolchain.toml"))
        rustSources.from(rustWorkspaceDir.resolve(".cargo/config.toml"))
        gitExecutable.set(resolveHostTool("git"))
        goExecutable.set(resolveHostTool("go"))
        cargoExecutable.set(resolveHostTool("cargo"))
        sdkDir.set(resolveAndroidSdkDir())
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cmakeVersion.set(providers.gradleProperty("ripdpi.nativeCmakeVersion"))
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        cargoProfile.set(rustNativeCargoProfile)
        buildMode.set(pluggableTransportAssetsMode)
        strictFailures.set(pluggableTransportAssetsStrictFailures)
        abis.set(rustNativeAbis)
        forbiddenAmbientCargoOverrideKeys.set(detectedAmbientCargoOverrideKeys)
        outputDir.set(generatedPtAssetsDir)
        workDir.set(ptAssetsBuildDir)
        providers
            .gradleProperty("ripdpi.prebuiltPluggableTransportAssetsDir")
            .orNull
            ?.takeIf(String::isNotBlank)
            ?.let { prebuiltSourceDir.set(file(it)) }
        providers
            .gradleProperty("ripdpi.prebuiltPluggableTransportAssetsManifestSha256")
            .orNull
            ?.takeIf(String::isNotBlank)
            ?.let { prebuiltManifestSha256.set(it) }
    }

// Wire the Rust build into the actual JNI packaging tasks so Rust-only source
// changes cannot be skipped when the Android variants are otherwise up to date.
//
// When `-Pripdpi.skipNativeBuild=true` is passed (e.g. by CI's unit-test or
// Roborazzi jobs that don't need native libraries), the wiring is omitted so
// downstream Android tasks don't transitively pull in the long Rust build.
val skipNativeBuildWiring = shouldSkipNativeBuild()
if (!skipNativeBuildWiring) {
    tasks.configureEach {
        if (
            name.matches(Regex("^merge.+JniLibFolders$")) ||
            name.matches(Regex("^copy.+JniLibsProjectOnly$")) ||
            name.matches(Regex("^merge.+NativeLibs$"))
        ) {
            dependsOn(buildRustNativeLibs)
        }
        if (name.matches(Regex("^merge.+Assets$"))) {
            dependsOn(buildRustRootHelper)
            dependsOn(buildRustNaiveProxy)
            dependsOn(buildRustCloudflareOrigin)
            dependsOn(buildPluggableTransportAssets)
        }
    }

    tasks.named("preBuild") {
        dependsOn(buildRustNativeLibs)
        dependsOn(buildRustRootHelper)
        dependsOn(buildRustNaiveProxy)
        dependsOn(buildRustCloudflareOrigin)
        dependsOn(buildPluggableTransportAssets)
    }
} else {
    logger.lifecycle(
        "ripdpi.skipNativeBuild=true: Rust native build dependencies are NOT wired into Android tasks. " +
            "Do not assemble an APK with this flag — JNI libs and helper assets will be missing.",
    )
}
