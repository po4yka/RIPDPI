package com.poyka.ripdpi.assets

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

// This receipt binds a release to installed bytes; it does not authenticate the publisher.
internal fun hasGeoAssetProvenance(
    target: File,
    source: String,
    tag: String,
): Boolean =
    try {
        DataInputStream(provenanceFile(target).inputStream().buffered()).use { input ->
            input.readInt() == ProvenanceVersion &&
                input.readUTF() == sha256(source) &&
                input.readUTF() == sha256(tag) &&
                input.readUTF() == sha256(target) &&
                input.read() == -1
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

internal fun persistGeoAssetProvenance(
    target: File,
    source: String,
    tag: String,
) = provenanceStorageOperation {
    val receipt = provenanceFile(target)
    val temp = File.createTempFile("geo-asset-", ".tmp", receipt.parentFile)
    try {
        DataOutputStream(temp.outputStream().buffered()).use { output ->
            output.writeInt(ProvenanceVersion)
            output.writeUTF(sha256(source))
            output.writeUTF(sha256(tag))
            output.writeUTF(sha256(target))
        }
        replaceGeoAssetTempFile(temp, receipt)
    } finally {
        temp.delete()
    }
}

internal fun invalidateGeoAssetProvenance(target: File) =
    provenanceStorageOperation {
        val receipt = provenanceFile(target)
        if (receipt.exists() && !receipt.delete()) {
            throw IOException("Unable to remove geo asset provenance.")
        }
    }

private fun provenanceFile(target: File): File = File(target.parentFile, "${target.name}.provenance")

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString()

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > GeoAssetMaxLocalImportBytes) throw IOException("Geo asset exceeds the local size limit.")
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHexString()
}

private inline fun provenanceStorageOperation(block: () -> Unit) {
    try {
        block()
    } catch (error: IOException) {
        throw GeoAssetIntegrityException(GeoAssetIntegrityFailure.InstallFailed, error)
    } catch (error: SecurityException) {
        throw GeoAssetIntegrityException(GeoAssetIntegrityFailure.InstallFailed, error)
    }
}

private const val ProvenanceVersion = 1
