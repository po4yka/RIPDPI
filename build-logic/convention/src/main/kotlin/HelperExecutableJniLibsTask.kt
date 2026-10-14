import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject

internal const val PluggableTransportManifestPath = "metadata/pluggable-transports.json"

/**
 * Packaged jniLibs file name of an app-launched helper executable. The runtime resolves the same
 * name in `ApplicationInfo.nativeLibraryDir` (`helperExecutableLibraryFileName` in `:core:service`).
 */
internal fun helperExecutableJniLibName(executableName: String): String {
    require(executableName.isNotBlank() && File(executableName).name == executableName) {
        "Unsafe helper executable name: $executableName"
    }
    return "lib$executableName.so"
}

/**
 * Stages every helper executable that the app process starts as `jniLibs/<abi>/lib<name>.so`.
 *
 * Android 10+ (targetSdk 29 and higher) denies exec() of files in the app home directory
 * (SELinux `execute_no_trans` on `app_data_file`), so helpers cannot be copied out of assets
 * and run. The package manager extracts jniLibs at install time into nativeLibraryDir, from
 * which an untrusted app may exec. jniLibs are also ABI-filtered by ABI splits and bundles.
 *
 * Direct helpers come from `<root>/<abi>/<name>`. Pluggable transports come from the PT asset
 * task output: the task stages each upstream binary named in `pluggable-transports.json` after it
 * verifies the recorded SHA-256, and republishes the manifest as the only PT asset.
 */
@CacheableTask
abstract class HelperExecutableJniLibsTask
    @Inject
    constructor(
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val directHelperRoots: ConfigurableFileCollection

        @get:Input
        abstract val directHelperNames: ListProperty<String>

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val pluggableTransportAssetsDir: DirectoryProperty

        @get:Input
        abstract val abis: ListProperty<String>

        @get:OutputDirectory
        abstract val jniLibsDir: DirectoryProperty

        @get:OutputDirectory
        abstract val pluggableTransportMetadataDir: DirectoryProperty

        @TaskAction
        fun stage() {
            val jniLibsRoot = jniLibsDir.get().asFile
            val metadataRoot = pluggableTransportMetadataDir.get().asFile
            val ptRoot = pluggableTransportAssetsDir.get().asFile
            val manifestFile = ptRoot.resolve(PluggableTransportManifestPath)
            if (!manifestFile.isFile) {
                throw GradleException("Pluggable transport manifest is missing: ${manifestFile.absolutePath}")
            }
            val records = parseArtifactRecords(manifestFile)

            fileSystemOperations.delete { delete(jniLibsRoot, metadataRoot) }
            for (abi in abis.get()) {
                val abiDir = jniLibsRoot.resolve(abi).apply { mkdirs() }
                stageDirectHelpers(abi, abiDir)
                stagePluggableTransports(abi, abiDir, ptRoot, records)
            }
            manifestFile.copyTo(metadataRoot.resolve(PluggableTransportManifestPath), overwrite = true)
        }

        private fun stageDirectHelpers(
            abi: String,
            abiDir: File,
        ) {
            for (name in directHelperNames.get()) {
                val candidates =
                    directHelperRoots.files
                        .map { root -> root.resolve(abi).resolve(name) }
                        .filter(File::isFile)
                if (candidates.size != 1) {
                    throw GradleException(
                        "Expected exactly one $abi/$name helper executable, found ${candidates.size}",
                    )
                }
                stageExecutable(candidates.single(), abiDir.resolve(helperExecutableJniLibName(name)))
            }
        }

        private fun stagePluggableTransports(
            abi: String,
            abiDir: File,
            ptRoot: File,
            records: List<JsonObject>,
        ) {
            val abiRecords = records.filter { it.stringOrNull("abi") == abi }
            if (abiRecords.isEmpty()) {
                throw GradleException("Pluggable transport manifest has no artifacts for $abi")
            }
            val stagedDigests = mutableMapOf<String, String>()
            for (record in abiRecords) {
                val outputName = record.stringOrNull("outputName")
                val upstream = record.stringOrNull("upstreamBinary") ?: continue
                val expectedDigest =
                    record.stringOrNull("upstreamSha256")
                        ?: throw GradleException("Pluggable transport $abi/$outputName has no upstreamSha256")
                val source = ptRoot.resolve("bin").resolve(abi).resolve(upstream)
                val target = abiDir.resolve(helperExecutableJniLibName(upstream))
                if (!source.isFile) {
                    throw GradleException("Pluggable transport upstream binary is missing: $abi/$upstream")
                }
                if (sha256(source) != expectedDigest) {
                    throw GradleException("Pluggable transport upstream digest mismatch: $abi/$upstream")
                }
                val previous = stagedDigests.putIfAbsent(upstream, expectedDigest)
                if (previous != null && previous != expectedDigest) {
                    throw GradleException("Pluggable transport upstream $abi/$upstream has conflicting digests")
                }
                if (previous == null) {
                    stageExecutable(source, target)
                }
            }
        }

        private fun stageExecutable(
            source: File,
            target: File,
        ) {
            source.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
        }

        private fun parseArtifactRecords(manifestFile: File): List<JsonObject> {
            val root = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
            val artifacts =
                root["artifacts"]?.jsonArray
                    ?: throw GradleException("Pluggable transport manifest has no artifacts array")
            return artifacts.map { it.jsonObject }
        }

        private fun JsonObject.stringOrNull(key: String): String? {
            val element = this[key]
            if (element == null || element is JsonNull) {
                return null
            }
            return element.jsonPrimitive.content.takeIf(String::isNotBlank)
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
    }
