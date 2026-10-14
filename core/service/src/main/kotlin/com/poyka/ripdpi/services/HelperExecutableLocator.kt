package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal const val PluggableTransportManifestAsset = "metadata/pluggable-transports.json"

/**
 * Directories where older builds copied helper executables before they ran them. Android 10+
 * (targetSdk 29 and higher) does not let an app exec() files from its home directory: SELinux
 * denies `execute_no_trans` on `app_data_file`, and ProcessBuilder fails with `error=13`.
 */
internal val LegacyExtractedHelperDirectories = listOf("subprocess-relays", "cloudflare-runtime")

private val legacyExtractedHelpersPurged = AtomicBoolean(false)

/**
 * Packaged file name of an app-launched helper executable.
 *
 * The build stages every helper that the app process starts (pluggable transports, NaiveProxy,
 * Cloudflare helpers) as `lib<name>.so` in jniLibs. The package manager extracts it at install
 * time (`extractNativeLibs=true`) into [android.content.pm.ApplicationInfo.nativeLibraryDir],
 * which is the only app-owned location an untrusted app can exec() from. Keep this rule in sync
 * with `helperExecutableJniLibName` in the `HelperExecutableJniLibsTask` build-logic task.
 */
internal fun helperExecutableLibraryFileName(executableName: String): String {
    require(executableName.isNotBlank() && File(executableName).name == executableName) {
        "Unsafe helper executable name: $executableName"
    }
    return "lib$executableName.so"
}

/**
 * ABI of the native libraries the package manager installed. `nativeLibraryDir` holds only that
 * ABI (for example `lib/arm64`), which can differ from `Build.SUPPORTED_ABIS[0]` when a device
 * installs a 32-bit split or a translated ABI.
 */
internal fun installedNativeLibraryAbi(
    nativeLibraryDir: String,
    supportedAbis: List<String>,
): String =
    when (File(nativeLibraryDir).name) {
        "arm64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        "x86" -> "x86"
        "x86_64" -> "x86_64"
        else -> supportedAbis.firstOrNull() ?: "arm64-v8a"
    }

internal class HelperExecutableLocator(
    private val nativeLibraryDir: File,
    private val abi: String,
    private val manifestPayload: () -> String?,
) {
    /** Resolves a helper that the build packages directly, for example `ripdpi-naiveproxy`. */
    fun locateHelper(executableName: String): File = packagedExecutable(executableName, executableName)

    /**
     * Resolves a pluggable transport through `pluggable-transports.json` to the upstream binary
     * that the build packaged for the installed ABI. The app executes the upstream directly; the
     * shell launcher only forwarded its arguments to it.
     */
    fun locatePluggableTransport(outputName: String): File {
        val upstream =
            try {
                resolvePluggableTransportUpstreamBinary(manifestPayload(), abi, outputName)
            } catch (error: IllegalArgumentException) {
                throw IllegalStateException("Invalid pluggable transport manifest for $outputName/$abi", error)
            }
        checkNotNull(upstream) {
            "Pluggable transport $outputName is unavailable for $abi: this build did not compile it from source"
        }
        return packagedExecutable(outputName, upstream)
    }

    private fun packagedExecutable(
        executableName: String,
        packagedName: String,
    ): File {
        val executable = File(nativeLibraryDir, helperExecutableLibraryFileName(packagedName))
        check(executable.isFile) {
            "Helper executable $executableName is not packaged for $abi: ${executable.absolutePath} is missing"
        }
        return executable
    }

    companion object {
        fun forContext(context: Context): HelperExecutableLocator {
            purgeLegacyExtractedHelpersOnce(context.filesDir)
            val nativeLibraryDir =
                checkNotNull(context.applicationInfo.nativeLibraryDir) {
                    "Application native library directory is unavailable"
                }
            return HelperExecutableLocator(
                nativeLibraryDir = File(nativeLibraryDir),
                abi = installedNativeLibraryAbi(nativeLibraryDir, Build.SUPPORTED_ABIS.toList()),
                manifestPayload = {
                    runCatching {
                        context.assets
                            .open(PluggableTransportManifestAsset)
                            .bufferedReader()
                            .use { it.readText() }
                    }.getOrNull()
                },
            )
        }
    }
}

/**
 * Returns the packaged upstream binary name for [outputName] on [abi], or null when the build
 * did not compile the transport from source. A missing or ambiguous manifest entry and an
 * unsafe upstream name fail closed with [IllegalArgumentException].
 */
internal fun resolvePluggableTransportUpstreamBinary(
    manifestPayload: String?,
    abi: String,
    outputName: String,
): String? {
    val payload = requireNotNull(manifestPayload) { "Pluggable transport manifest is unavailable" }

    val artifact =
        requireNotNull(
            requireNotNull(Json.parseToJsonElement(payload).jsonObject["artifacts"]) {
                "Pluggable transport manifest has no artifacts array"
            }.jsonArray
                .asSequence()
                .map { it.jsonObject }
                .singleOrNull { candidate ->
                    candidate["abi"]?.jsonPrimitive?.content == abi &&
                        candidate["outputName"]?.jsonPrimitive?.content == outputName
                },
        ) {
            "Manifest has no unique artifact for $outputName/$abi"
        }

    val upstreamElement = artifact["upstreamBinary"]
    if (upstreamElement == null || upstreamElement is JsonNull) {
        return null
    }
    val manifestUpstream = upstreamElement.jsonPrimitive.content
    require(manifestUpstream.isNotBlank() && File(manifestUpstream).name == manifestUpstream) {
        "Unsafe upstream asset name for $outputName/$abi"
    }
    return manifestUpstream
}

/**
 * Deletes helper copies that older builds extracted into [filesDir]. They can no longer run, and
 * they waste storage. The purge runs once per process and never fails the caller.
 */
internal fun purgeLegacyExtractedHelpers(filesDir: File) {
    for (name in LegacyExtractedHelperDirectories) {
        val directory = File(filesDir, name)
        if (directory.exists() && !directory.deleteRecursively()) {
            Logger.withTag("HelperExecutableLocator").w { "Unable to remove legacy helper directory $directory" }
        }
    }
}

private fun purgeLegacyExtractedHelpersOnce(filesDir: File) {
    if (legacyExtractedHelpersPurged.compareAndSet(false, true)) {
        purgeLegacyExtractedHelpers(filesDir)
    }
}
