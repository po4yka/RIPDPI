import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest

private val LyrebirdPatchPaths =
    setOf("transports/transports.go", "cmd/lyrebird/lyrebird.go", "cmd/lyrebird/pt_extras.go")

class LyrebirdObfs4Patch(
    val file: File,
    private val commit: String,
    private val preimages: Map<String, String>,
    private val postimages: Map<String, String>,
) {
    fun verifyInput(
        repo: File,
        actualHead: String,
    ) {
        require(actualHead == commit) { "Lyrebird patch requires the reviewed upstream commit" }
        verifyFiles(repo, preimages)
    }

    fun verifyOutput(repo: File) = verifyFiles(repo, postimages)

    private fun verifyFiles(
        repo: File,
        expected: Map<String, String>,
    ) {
        expected.forEach { (path, digest) ->
            require(
                sourcePatchSha256(repo.resolve(path)) == digest,
            ) { "Lyrebird patch source checksum mismatch: $path" }
        }
    }
}

fun reviewedPluggableTransportSourcePatch(
    source: PluggableTransportSource,
    directory: File,
): LyrebirdObfs4Patch? {
    if (source.id != "lyrebird") {
        require(source.sourcePatchSha256 == null) { "Unexpected patch for pluggable transport ${source.id}" }
        return null
    }
    require(
        source.outputNames == listOf("ripdpi-obfs4"),
    ) { "The reviewed Lyrebird patch supports only the OBFS4 asset" }
    val manifest = directory.resolve("lyrebird-obfs4-only.json")
    require(source.sourcePatchSha256 == sourcePatchSha256(manifest)) { "Lyrebird patch manifest checksum mismatch" }
    val record = JsonSlurper().parse(manifest) as Map<*, *>
    require((record["schemaVersion"] as? Number)?.toInt() == 1 && record["sourceId"] == source.id) {
        "Unexpected Lyrebird patch manifest"
    }
    require(record["commit"] == source.commit) { "Lyrebird source pin does not match the reviewed patch" }
    require(record["patch"] == "lyrebird-obfs4-only.patch") { "Unexpected Lyrebird patch file" }
    val patch = directory.resolve("lyrebird-obfs4-only.patch")
    require(record["patchSha256"] == sourcePatchSha256(patch)) { "Lyrebird patch checksum mismatch" }
    return LyrebirdObfs4Patch(
        patch,
        requireNotNull(source.commit),
        patchImageChecksums(record["preimages"]),
        patchImageChecksums(record["postimages"]),
    )
}

fun verifyPluggableTransportSourcePatchReceipt(
    record: Map<*, *>,
    source: PluggableTransportSource,
) {
    require(record["sourcePatchSha256"] == source.sourcePatchSha256) {
        "Prebuilt pluggable transport source ${source.id} has an unexpected patch manifest"
    }
}

private fun patchImageChecksums(value: Any?): Map<String, String> {
    val images = value as? Map<*, *> ?: error("Lyrebird patch is missing image checksums")
    require(images.keys == LyrebirdPatchPaths) { "Unexpected Lyrebird patch source paths" }
    return LyrebirdPatchPaths.associateWith { path ->
        val digest = images[path] as? String ?: error("Lyrebird patch checksum is missing: $path")
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid Lyrebird patch checksum: $path" }
        digest
    }
}

fun sourcePatchSha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
