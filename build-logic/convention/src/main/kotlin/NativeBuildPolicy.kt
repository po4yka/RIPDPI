import org.gradle.api.GradleException
import org.gradle.api.Project

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
    val useOverride = overrideAbis.isNotEmpty() && !isCiBuild() && !isReleaseLikeBuild()

    return if (useOverride) {
        logger.lifecycle("Using local native ABI override for non-release build: ${overrideAbis.joinToString()}")
        overrideAbis
    } else {
        defaultAbis
    }
}

internal fun Project.resolveAndroidSdkDir(): String {
    val localProperties = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (localProperties.isFile) {
        localProperties.useLines { lines ->
            lines.firstOrNull { it.startsWith("sdk.dir=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { return it }
        }
    }

    providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }
    providers.environmentVariable("ANDROID_HOME").orNull
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }

    throw GradleException(
        "SDK location not found. Set sdk.dir in local.properties or ANDROID_SDK_ROOT/ANDROID_HOME.",
    )
}
