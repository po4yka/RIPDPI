import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider

internal fun parseAbiList(value: String): List<String> =
    value.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

internal fun Project.isCiBuild(): Boolean = providers.environmentVariable("CI").isPresent

internal fun Project.isReleaseLikeBuild(): Boolean {
    val taskNames = gradle.startParameter.taskNames
    if (taskNames.isEmpty()) {
        return false
    }

    return taskNames.any { taskName ->
        val normalized = taskName.lowercase()
        normalized.contains("release") ||
            normalized.contains("bundle") ||
            normalized.contains("publish")
    }
}

internal fun Project.resolvedNativeAbis(): List<String> {
    val defaultAbis = parseAbiList(providers.gradleProperty("ripdpi.nativeAbis").get())
    val overrideAbis = providers.gradleProperty("ripdpi.localNativeAbis").orNull?.let(::parseAbiList).orEmpty()
    val defaultLocalAbis =
        providers.gradleProperty("ripdpi.localNativeAbisDefault").orNull?.let(::parseAbiList).orEmpty()
    val canUseLocalAbis = !isCiBuild() && !isReleaseLikeBuild()

    return when {
        overrideAbis.isNotEmpty() && canUseLocalAbis -> {
            logger.lifecycle("Using local native ABI override for non-release build: ${overrideAbis.joinToString()}")
            overrideAbis
        }
        defaultLocalAbis.isNotEmpty() && canUseLocalAbis -> {
            logger.lifecycle("Using default local native ABI set for non-release build: ${defaultLocalAbis.joinToString()}")
            defaultLocalAbis
        }
        else -> {
            defaultAbis
        }
    }
}

internal fun Project.resolveAndroidSdkDir(): Provider<String> =
    providers.provider {
        val localProperties = rootProject.layout.projectDirectory.file("local.properties").asFile
        val sdkDirFromLocalProperties =
            if (localProperties.isFile) {
                localProperties.useLines { lines ->
                    lines.firstOrNull { it.startsWith("sdk.dir=") }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                }
            } else {
                null
            }

        sdkDirFromLocalProperties
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull?.trim()?.takeIf(String::isNotEmpty)
            ?: providers.environmentVariable("ANDROID_HOME").orNull?.trim()?.takeIf(String::isNotEmpty)
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
