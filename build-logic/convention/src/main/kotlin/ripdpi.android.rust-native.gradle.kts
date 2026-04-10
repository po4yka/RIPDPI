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

val rustNativeAbis = resolvedNativeAbis()
val rustNativeCargoProfile = resolvedNativeCargoProfile()
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
        "ripdpi-relay-core",
        "ripdpi-warp-core",
        "ripdpi-native-protect",
        "ripdpi-vless",
        "ripdpi-masque",
        "ripdpi-tls-profiles",
    ).map { packageName ->
        rustWorkspaceDir.resolve("crates").resolve(packageName)
    }
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val generatedAssetsDir = layout.buildDirectory.dir("generated/rootHelperAssets")
val rustNativeLibsBuildDir = layout.buildDirectory.dir("intermediates/rust-native-libs")
val rustRootHelperBuildDir = layout.buildDirectory.dir("intermediates/rust-root-helper")
val rustRootHelperArtifactSpecs =
    listOf(
        "ripdpi-root-helper|ripdpi-root-helper|ripdpi-root-helper",
    )

extensions.configure<LibraryExtension> {
    sourceSets["main"].jniLibs.directories.add(generatedJniLibsDir.get().asFile.absolutePath)
    sourceSets["main"].assets.directories.add(generatedAssetsDir.get().asFile.absolutePath)
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
    }
}

tasks.named("preBuild") {
    dependsOn(buildRustNativeLibs)
    dependsOn(buildRustRootHelper)
}
