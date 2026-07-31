import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File

private val aggregateReleaseTaskPaths =
    setOf(
        "assemble",
        "build",
        "app:assemble",
        "app:build",
    )

private fun String.isReleaseProducingAppAggregate(): Boolean =
    startsWith("app:assemble") && !contains("debug") && !contains("androidtest")

internal fun parseAbiList(value: String): List<String> =
    value
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

internal data class NativeConcurrency(
    val abiWorkers: Int,
    val cargoJobsPerWorker: Int,
)

internal fun computeNativeConcurrency(
    abiCount: Int,
    availableCpus: Int,
    abiParallelism: Int,
    cpuBudget: Int,
): NativeConcurrency {
    require(abiCount > 0) { "abiCount must be at least 1" }
    require(availableCpus > 0) { "availableCpus must be at least 1" }
    require(abiParallelism > 0) { "ripdpi.nativeAbiParallelism must be at least 1" }
    require(cpuBudget > 0) { "ripdpi.nativeCpuBudget must be at least 1" }

    val effectiveBudget = minOf(availableCpus, cpuBudget)
    val abiWorkers = minOf(abiCount, abiParallelism, effectiveBudget)
    return NativeConcurrency(
        abiWorkers = abiWorkers,
        cargoJobsPerWorker = (effectiveBudget / abiWorkers).coerceAtLeast(1),
    )
}

internal fun Project.isCiBuild(): Boolean = providers.environmentVariable("CI").isPresent

internal fun Project.isReleaseLikeBuild(): Boolean {
    val taskNames = gradle.startParameter.taskNames
    if (taskNames.isEmpty()) {
        return false
    }

    return taskNames.any { taskName ->
        val normalized = taskName.lowercase().removePrefix(":")
        normalized.contains("release") ||
            normalized.contains("bundle") ||
            normalized.contains("publish") ||
            normalized in aggregateReleaseTaskPaths ||
            normalized.isReleaseProducingAppAggregate()
    }
}

internal fun Project.resolvedNativeCargoProfile(): String {
    val defaultProfile = providers.gradleProperty("ripdpi.nativeCargoProfile").get()
    val localDefaultProfile = providers.gradleProperty("ripdpi.localNativeCargoProfileDefault").orNull
    val canUseLocalProfile = !isCiBuild() && !isReleaseLikeBuild()

    return if (localDefaultProfile != null && canUseLocalProfile) {
        logger.lifecycle("Using local native Cargo profile for non-release build: $localDefaultProfile")
        localDefaultProfile
    } else {
        defaultProfile
    }
}

// Maps the JVM's reported os.arch to the matching Android ABI identifier.
// Returns null on unrecognized hosts so the resolver can fall back gracefully.
private fun hostMatchingAndroidAbi(): String? =
    when (System.getProperty("os.arch")?.lowercase()) {
        "aarch64", "arm64" -> "arm64-v8a"
        "x86_64", "amd64" -> "x86_64"
        else -> null
    }

internal fun Project.resolvedNativeAbis(): List<String> {
    val ciOverrideAbis =
        providers
            .gradleProperty("ripdpi.nativeAbisOverride")
            .orNull
            ?.let(::parseAbiList)
            .orEmpty()
    if (ciOverrideAbis.isNotEmpty()) {
        logger.lifecycle("Using native ABI override: ${ciOverrideAbis.joinToString()}")
        return ciOverrideAbis
    }

    val defaultAbis = parseAbiList(providers.gradleProperty("ripdpi.nativeAbis").get())
    val overrideAbis =
        providers
            .gradleProperty("ripdpi.localNativeAbis")
            .orNull
            ?.let(::parseAbiList)
            .orEmpty()
    val defaultLocalRaw =
        providers
            .gradleProperty("ripdpi.localNativeAbisDefault")
            .orNull
            ?.trim()
            .orEmpty()
    // Special token: "host" derives the local default from the JVM's os.arch.
    // This makes Apple Silicon devs get arm64-v8a and Intel Mac / Linux x86_64
    // devs get x86_64 without committing per-host overrides.
    val defaultLocalAbis: List<String> =
        when {
            defaultLocalRaw.isEmpty() -> {
                emptyList()
            }

            defaultLocalRaw.equals("host", ignoreCase = true) -> {
                val host = hostMatchingAndroidAbi()
                if (host == null) {
                    logger.lifecycle(
                        "ripdpi.localNativeAbisDefault=host but os.arch=${System.getProperty("os.arch")} " +
                            "did not map to an Android ABI; falling back to ripdpi.nativeAbis.",
                    )
                    emptyList()
                } else {
                    listOf(host)
                }
            }

            else -> {
                parseAbiList(defaultLocalRaw)
            }
        }
    val canUseLocalAbis = !isCiBuild() && !isReleaseLikeBuild()

    return when {
        overrideAbis.isNotEmpty() && canUseLocalAbis -> {
            logger.lifecycle("Using local native ABI override for non-release build: ${overrideAbis.joinToString()}")
            overrideAbis
        }

        defaultLocalAbis.isNotEmpty() && canUseLocalAbis -> {
            val suffix =
                if (defaultLocalRaw.equals("host", ignoreCase = true)) {
                    " (host=${System.getProperty("os.arch")})"
                } else {
                    ""
                }
            logger.lifecycle(
                "Using default local native ABI set for non-release build: " +
                    "${defaultLocalAbis.joinToString()}$suffix",
            )
            defaultLocalAbis
        }

        else -> {
            defaultAbis
        }
    }
}

internal fun Project.shouldSkipNativeBuild(): Boolean =
    providers
        .gradleProperty("ripdpi.skipNativeBuild")
        .orNull
        ?.toBooleanStrictOrNull()
        ?: false

internal fun Project.resolveAndroidSdkDir(): Provider<String> =
    providers.provider {
        val localProperties =
            rootProject.layout.projectDirectory
                .file("local.properties")
                .asFile
        val sdkDirFromLocalProperties =
            if (localProperties.isFile) {
                localProperties.useLines { lines ->
                    lines
                        .firstOrNull { it.startsWith("sdk.dir=") }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                }
            } else {
                null
            }

        sdkDirFromLocalProperties
            ?: providers
                .environmentVariable("ANDROID_SDK_ROOT")
                .orNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            ?: providers
                .environmentVariable("ANDROID_HOME")
                .orNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            ?: throw GradleException(
                "SDK location not found. Set sdk.dir in local.properties or ANDROID_SDK_ROOT/ANDROID_HOME.",
            )
    }

internal fun Project.resolveRustTool(name: String): Provider<String> =
    providers.provider {
        val cargoHome = providers.environmentVariable("CARGO_HOME").orNull?.takeIf(String::isNotBlank)
        val homeDir = providers.systemProperty("user.home").orNull?.takeIf(String::isNotBlank)
        val candidates =
            listOfNotNull(
                cargoHome?.let { File(it).resolve("bin").resolve(name) },
                homeDir?.let { File(it).resolve(".cargo").resolve("bin").resolve(name) },
            )

        candidates.firstOrNull(File::canExecute)?.absolutePath ?: name
    }

internal fun Project.resolveHostTool(name: String): Provider<String> =
    providers.provider {
        val homeDir = providers.systemProperty("user.home").orNull?.takeIf(String::isNotBlank)
        val candidates =
            listOfNotNull(
                homeDir?.let { File(it).resolve(".local/bin").resolve(name) },
                File("/opt/homebrew/bin").resolve(name),
                File("/usr/local/bin").resolve(name),
                File("/usr/bin").resolve(name),
            )

        candidates.firstOrNull(File::canExecute)?.absolutePath ?: name
    }

internal fun Project.resolvedPluggableTransportAssetsMode(): String {
    val override =
        providers
            .gradleProperty("ripdpi.pluggableTransportAssetsMode")
            .orNull
            ?.trim()
            ?.lowercase()
    val allowed = setOf("source", "stub", "auto")
    if (override != null) {
        require(override in allowed) {
            "Unsupported ripdpi.pluggableTransportAssetsMode=$override. Expected one of ${allowed.joinToString()}."
        }
        return override
    }

    return if (isCiBuild() || isReleaseLikeBuild()) {
        "source"
    } else {
        "stub"
    }
}

internal fun Project.resolvedPluggableTransportAssetsStrictFailures(): Boolean =
    providers.gradleProperty("ripdpi.pluggableTransportAssetsStrictFailures").orNull?.toBooleanStrictOrNull()
        ?: (isCiBuild() || isReleaseLikeBuild())
