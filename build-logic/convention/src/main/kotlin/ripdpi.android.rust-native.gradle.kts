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
import java.security.MessageDigest
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
    val repoUrl: String,
    val commit: String,
    val goToolchain: String?,
    val cgoEnabled: Boolean,
    val packagePath: String,
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
            val installedTargets = installedRustTargets(rustupExecutable.get())
            val hostBinDir = resolveNdkToolchainBinDir()
            val manifest = workspaceManifest.get().asFile
            val artifacts = artifactSpecs.get().map(::parseArtifactSpec)
            val packageNames = artifacts.map(RustNativeArtifact::packageName).distinct()
            val expectedOutputNames = artifacts.map(RustNativeArtifact::outputName).toSet()
            val outputRoot = outputDir.get().asFile
            val cargoTargetRoot = cargoTargetDir.get().asFile
            val cargoExecutablePath = cargoExecutable.get()
            val cargoProfileName = cargoProfile.get()
            val abiList = abis.get()

            // Resolve the Android SDK cmake binary on the Gradle task thread.  Gradle lazy
            // property access (sdkDir.get()) is not safe from background threads, and the
            // cmake crate reads the CMAKE env var to select the cmake binary.  Using the
            // Android SDK cmake avoids macOS sysroot injection by Homebrew cmake.
            val androidSdkCmake: String? =
                File(sdkDir.get())
                    .resolve("cmake")
                    .takeIf { it.isDirectory }
                    ?.listFiles()
                    ?.filter { it.isDirectory }
                    ?.maxByOrNull { it.name }
                    ?.resolve("bin/cmake")
                    ?.takeIf { it.isFile }
                    ?.absolutePath

            if (androidSdkCmake != null) {
                logger.lifecycle("boring-sys cmake: $androidSdkCmake")
            } else {
                logger.warn("Android SDK cmake not found under ${File(sdkDir.get(), "cmake")} — using system cmake")
            }

            pruneStaleAbiOutputs(outputRoot)

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
            // Cap thread count to available processors to avoid CPU contention.
            val availableCpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threadCount = abiConfigs.size.coerceAtMost(availableCpus)
            val cargoJobs = (availableCpus / abiConfigs.size).coerceAtLeast(1)
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
                                artifacts,
                                cargoJobs,
                                androidSdkCmake,
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
            artifacts: List<RustNativeArtifact>,
            cargoJobs: Int,
            androidCmakePath: String?,
        ) {
            val targetEnv = config.target.replace('-', '_').uppercase()
            val ccTargetKey = "CC_${config.target.replace('-', '_')}"
            val cxxTargetKey = "CXX_${config.target.replace('-', '_')}"
            val arTargetKey = "AR_${config.target.replace('-', '_')}"
            val abiCargoTargetDir = cargoTargetRoot.resolve(config.abi)
            val abiOutputDir = outputRoot.resolve(config.abi)
            abiOutputDir.mkdirs()
            pruneStaleArtifactOutputs(abiOutputDir, expectedOutputNames)

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
                            // Delete the entire boring-sys OUT_DIR (cacheFile.parentFile is the
                            // cmake build subdir; its parent is the Cargo OUT_DIR).  Deleting
                            // just the cmake build subdir left libcrypto.a missing but Cargo's
                            // build-script fingerprint still valid, so the link step failed.
                            // Deleting OUT_DIR causes Cargo to re-run boring-sys's build script
                            // on the next invocation, which rebuilds BoringSSL from scratch.
                            val outDir = cacheFile.parentFile.parentFile
                            logger.warn(
                                "Deleting stale BoringSSL OUT_DIR " +
                                    "(cmake=$cachedCmake, cc=$cachedCc, cxx=$cachedCxx): " +
                                    outDir.absolutePath,
                            )
                            fileSystemOperations.delete { delete(outDir) }
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
                    if (androidCmakePath != null) {
                        put("CMAKE", androidCmakePath)
                    }
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
    }

@CacheableTask
abstract class BuildPluggableTransportAssetsTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sourcesManifest: RegularFileProperty

        @get:Input
        abstract val gitExecutable: Property<String>

        @get:Input
        abstract val goExecutable: Property<String>

        @get:Input
        abstract val sdkDir: Property<String>

        @get:Input
        abstract val ndkVersion: Property<String>

        @get:Input
        abstract val minSdk: Property<Int>

        @get:Input
        abstract val buildMode: Property<String>

        @get:Input
        abstract val strictFailures: Property<Boolean>

        @get:Input
        abstract val abis: ListProperty<String>

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
            val strictMode = strictFailures.get()
            val buildFromSource =
                when (mode) {
                    "source" -> {
                        true
                    }

                    "stub" -> {
                        false
                    }

                    "auto" -> {
                        executableAvailable(gitExecutable.get(), "--version") &&
                            executableAvailable(goExecutable.get(), "version")
                    }

                    else -> {
                        throw GradleException("Unsupported pluggable transport build mode: $mode")
                    }
                }

            if (mode == "source" && !executableAvailable(gitExecutable.get(), "--version")) {
                throw GradleException("git is required for ripdpi.pluggableTransportAssetsMode=source")
            }
            if (mode == "source" && !executableAvailable(goExecutable.get(), "version")) {
                throw GradleException("go is required for ripdpi.pluggableTransportAssetsMode=source")
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
                                    val repoDir = syncPinnedRepo(reposDir, source)
                                    buildGoBinary(
                                        source = source,
                                        abi = abi,
                                        repoDir = repoDir,
                                        cacheDir = cacheDir.resolve(abi).resolve(source.id),
                                        modCacheDir = modCacheDir,
                                    )
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
                        for (outputName in source.outputNames) {
                            val launcher = abiBinDir.resolve(outputName)
                            val upstreamBinary = abiBinDir.resolve("$outputName.upstream")
                            if (sourceBuildResult != null) {
                                copyIfChanged(sourceBuildResult, upstreamBinary)
                                launcher.writeText(
                                    sourceBuildLauncherScript(upstreamBinary.name),
                                    Charsets.UTF_8,
                                )
                            } else if (buildError != null) {
                                if (upstreamBinary.exists()) {
                                    upstreamBinary.delete()
                                }
                                launcher.writeText(
                                    sourceBuildFailedScript(outputName, source, buildError!!),
                                    Charsets.UTF_8,
                                )
                            } else {
                                if (upstreamBinary.exists()) {
                                    upstreamBinary.delete()
                                }
                                launcher.writeText(sourceBuildUnavailableScript(outputName, source), Charsets.UTF_8)
                            }
                            launcher.setExecutable(true, true)
                            upstreamBinary.takeIf(File::exists)?.setExecutable(true, true)
                            records +=
                                mapOf(
                                    "abi" to abi,
                                    "outputName" to outputName,
                                    "sourceId" to source.id,
                                    "repoUrl" to source.repoUrl,
                                    "commit" to source.commit,
                                    "goToolchain" to source.goToolchain,
                                    "cgoEnabled" to source.cgoEnabled,
                                    "packagePath" to source.packagePath,
                                    "mode" to if (sourceBuildResult != null) "source" else "stub",
                                    "buildError" to buildError,
                                    "launcherSha256" to sha256(launcher),
                                    "upstreamBinary" to upstreamBinary.name.takeIf { upstreamBinary.exists() },
                                    "upstreamSha256" to upstreamBinary.takeIf(File::exists)?.let(::sha256),
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
                                            "repoUrl" to source.repoUrl,
                                            "commit" to source.commit,
                                            "goToolchain" to source.goToolchain,
                                            "cgoEnabled" to source.cgoEnabled,
                                            "packagePath" to source.packagePath,
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
                PluggableTransportSource(
                    id = data.requireString("id"),
                    repoUrl = data.requireString("repoUrl"),
                    commit = data.requireString("commit"),
                    goToolchain = data["goToolchain"]?.toString()?.takeIf(String::isNotBlank),
                    cgoEnabled = data["cgoEnabled"]?.toString()?.toBooleanStrictOrNull() ?: false,
                    packagePath = data.requireString("packagePath"),
                    sourceBinaryName = data.requireString("sourceBinaryName"),
                    outputNames = (data["outputNames"] as? List<*>)?.map { it.toString() }.orEmpty(),
                ).also { source ->
                    require(
                        source.outputNames.isNotEmpty(),
                    ) { "Source ${source.id} must declare at least one output name" }
                }
            }
        }

        private fun syncPinnedRepo(
            reposDir: File,
            source: PluggableTransportSource,
        ): File {
            val repoDir = reposDir.resolve(source.id)
            if (!repoDir.resolve(".git").exists()) {
                fileSystemOperations.delete { delete(repoDir) }
                execOperations
                    .exec {
                        commandLine(
                            gitExecutable.get(),
                            "clone",
                            "--filter=blob:none",
                            source.repoUrl,
                            repoDir.absolutePath,
                        )
                    }.assertNormalExitValue()
            }
            execOperations
                .exec {
                    workingDir = repoDir
                    commandLine(gitExecutable.get(), "fetch", "--depth", "1", "origin", source.commit)
                }.assertNormalExitValue()
            execOperations
                .exec {
                    workingDir = repoDir
                    commandLine(gitExecutable.get(), "checkout", "--force", source.commit)
                }.assertNormalExitValue()
            execOperations
                .exec {
                    workingDir = repoDir
                    commandLine(gitExecutable.get(), "clean", "-fdx")
                }.assertNormalExitValue()
            return repoDir
        }

        private fun buildGoBinary(
            source: PluggableTransportSource,
            abi: String,
            repoDir: File,
            cacheDir: File,
            modCacheDir: File,
        ): File {
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
                        source.packagePath,
                    )
                }.assertNormalExitValue()
            if (!outputFile.isFile) {
                throw GradleException(
                    "Expected pluggable transport binary was not produced: ${outputFile.absolutePath}",
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
            |echo "$outputName could not be source-built from ${source.id}@${source.commit}." >&2
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
            |echo "to compile ${source.id} from ${source.repoUrl}." >&2
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
            return digest.digest().joinToString("") { "%02x".format(it) }
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
    }

val rustNativeAbis = resolvedNativeAbis()
val rustNativeCargoProfile = resolvedNativeCargoProfile()
val pluggableTransportAssetsMode = resolvedPluggableTransportAssetsMode()
val pluggableTransportAssetsStrictFailures = resolvedPluggableTransportAssetsStrictFailures()
val rustNativeArtifactSpecs =
    listOf(
        "ripdpi-android|libripdpi_android.so|libripdpi.so",
        "ripdpi-relay-android|libripdpi_relay_android.so|libripdpi-relay.so",
        "ripdpi-warp-android|libripdpi_warp_android.so|libripdpi-warp.so",
        "ripdpi-tunnel-android|libripdpi_tunnel_android.so|libripdpi-tunnel.so",
    )
val rustWorkspaceManifestFile =
    rootProject.layout.projectDirectory
        .file("native/rust/Cargo.toml")
        .asFile
val rustWorkspaceDir = rustWorkspaceManifestFile.parentFile
// Keep this list aligned with `cargo tree -p ripdpi-android` and `cargo tree -p ripdpi-tunnel-android`
// so unrelated workspace members do not invalidate the native build cache.
val rustNativePackageDirs =
    listOf(
        "android-support",
        "ripdpi-android",
        "ripdpi-apps-script-core",
        "ripdpi-relay-android",
        "ripdpi-config",
        "ripdpi-desync",
        "ripdpi-dns-resolver",
        "ripdpi-failure-classifier",
        "ripdpi-monitor",
        "ripdpi-packets",
        "ripdpi-proxy-config",
        "ripdpi-runtime",
        "ripdpi-session",
        "ripdpi-telemetry",
        "ripdpi-warp-android",
        "ripdpi-tun-driver",
        "ripdpi-tunnel-android",
        "ripdpi-tunnel-config",
        "ripdpi-tunnel-core",
        "ripdpi-ws-tunnel",
        "ripdpi-ipfrag",
        "ripdpi-root-helper",
        "ripdpi-relay-mux",
        "ripdpi-relay-core",
        "ripdpi-warp-core",
        "ripdpi-native-protect",
        "ripdpi-naiveproxy",
        "ripdpi-shadowtls",
        "ripdpi-tuic",
        "ripdpi-vless",
        "ripdpi-masque",
        "ripdpi-tls-profiles",
        "ripdpi-cloudflare-origin",
    ).map { packageName ->
        rustWorkspaceDir.resolve("crates").resolve(packageName)
    }
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val generatedAssetsDir = layout.buildDirectory.dir("generated/rootHelperAssets")
val generatedPtAssetsDir = layout.buildDirectory.dir("generated/pluggableTransportAssets")
val rustNativeLibsBuildDir = layout.buildDirectory.dir("intermediates/rust-native-libs")
val rustRootHelperBuildDir = layout.buildDirectory.dir("intermediates/rust-root-helper")
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
    sourceSets["main"].assets.directories.add(generatedAssetsDir.get().asFile.absolutePath)
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
            rustNativePackageDirs.map { packageDir ->
                fileTree(packageDir) {
                    exclude("**/target/**")
                }
            },
        )
        workspaceManifest.set(rustWorkspaceManifestFile)
        sdkDir.set(resolveAndroidSdkDir())
        cargoExecutable.set(resolveRustTool("cargo"))
        rustupExecutable.set(resolveRustTool("rustup"))
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cargoProfile.set(rustNativeCargoProfile)
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        artifactSpecs.set(rustNativeArtifactSpecs)
        cargoTargetDir.set(rustNativeLibsBuildDir)
        outputDir.set(generatedJniLibsDir)
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
        // The root helper depends on ripdpi-runtime and ripdpi-ipfrag sources.
        nativeSources.from(
            rustNativePackageDirs.map { packageDir ->
                fileTree(packageDir) {
                    exclude("**/target/**")
                }
            },
        )
        workspaceManifest.set(rustWorkspaceManifestFile)
        sdkDir.set(resolveAndroidSdkDir())
        cargoExecutable.set(resolveRustTool("cargo"))
        rustupExecutable.set(resolveRustTool("rustup"))
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cargoProfile.set(rustNativeCargoProfile)
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        artifactSpecs.set(rustRootHelperArtifactSpecs)
        cargoTargetDir.set(rustRootHelperBuildDir)
        // Output to assets/bin/<abi>/ so Kotlin can extract at runtime.
        outputDir.set(generatedAssetsDir.map { it.dir("bin") })
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
            rustNativePackageDirs.map { packageDir ->
                fileTree(packageDir) {
                    exclude("**/target/**")
                }
            },
        )
        workspaceManifest.set(rustWorkspaceManifestFile)
        sdkDir.set(resolveAndroidSdkDir())
        cargoExecutable.set(resolveRustTool("cargo"))
        rustupExecutable.set(resolveRustTool("rustup"))
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cargoProfile.set(rustNativeCargoProfile)
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        artifactSpecs.set(rustNaiveProxyArtifactSpecs)
        cargoTargetDir.set(layout.buildDirectory.dir("intermediates/rust-naiveproxy"))
        outputDir.set(generatedAssetsDir.map { it.dir("bin") })
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
            rustNativePackageDirs.map { packageDir ->
                fileTree(packageDir) {
                    exclude("**/target/**")
                }
            },
        )
        workspaceManifest.set(rustWorkspaceManifestFile)
        sdkDir.set(resolveAndroidSdkDir())
        cargoExecutable.set(resolveRustTool("cargo"))
        rustupExecutable.set(resolveRustTool("rustup"))
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        cargoProfile.set(rustNativeCargoProfile)
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        abis.set(rustNativeAbis)
        artifactSpecs.set(rustCloudflareOriginArtifactSpecs)
        cargoTargetDir.set(layout.buildDirectory.dir("intermediates/rust-cloudflare-origin"))
        outputDir.set(generatedAssetsDir.map { it.dir("bin") })
    }

val buildPluggableTransportAssets =
    tasks.register<BuildPluggableTransportAssetsTask>("buildPluggableTransportAssets") {
        group = "build"
        description = "Builds source-pinned pluggable transport helper assets or stub launchers."

        sourcesManifest.set(ptSourcesManifestFile)
        gitExecutable.set(resolveHostTool("git"))
        goExecutable.set(resolveHostTool("go"))
        sdkDir.set(resolveAndroidSdkDir())
        ndkVersion.set(providers.gradleProperty("ripdpi.nativeNdkVersion"))
        minSdk.set(providers.gradleProperty("ripdpi.minSdk").map(String::toInt))
        buildMode.set(pluggableTransportAssetsMode)
        strictFailures.set(pluggableTransportAssetsStrictFailures)
        abis.set(rustNativeAbis)
        outputDir.set(generatedPtAssetsDir)
        workDir.set(ptAssetsBuildDir)
    }

// Wire the Rust build into the actual JNI packaging tasks so Rust-only source
// changes cannot be skipped when the Android variants are otherwise up to date.
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
