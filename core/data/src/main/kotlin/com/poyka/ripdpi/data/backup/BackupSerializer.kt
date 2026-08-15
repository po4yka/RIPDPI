package com.poyka.ripdpi.data.backup

import com.poyka.ripdpi.serialization.RipDpiBackupJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Public serializer for the [BackupV1] document.
 *
 * The exporter ([BackupExporter]) and importer ([BackupImporter]) keep their JSON
 * configuration private; this object is the single public surface for turning a
 * [BackupV1] into JSON bytes. It deliberately offers a streaming variant
 * ([encodeToStream]) so a SAF export never materializes the whole archive in
 * memory: the document is written straight into the destination [OutputStream]
 * by `kotlinx-serialization`'s streaming encoder.
 */
object BackupSerializer {
    private val json =
        RipDpiBackupJson

    /**
     * Encodes [document] to a JSON string. Convenience for tests and small payloads;
     * prefer [encodeToStream] for the SAF export path.
     */
    fun encodeToString(document: BackupV1): String = json.encodeToString(BackupV1.serializer(), document)

    /**
     * Streams [document] as JSON into [output] without holding the encoded archive
     * in memory. The caller owns [output] and is responsible for closing it.
     *
     * Returns the number of JSON bytes written, so the caller can log a payload
     * size (never the payload itself) and surface the destination size to the user.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun encodeToStream(
        document: BackupV1,
        output: OutputStream,
    ): Long {
        val counting = CountingOutputStream(output)
        json.encodeToStream(BackupV1.serializer(), document, counting)
        counting.flush()
        return counting.bytesWritten
    }
}

/**
 * An [OutputStream] wrapper that counts the bytes written through it. Used so the
 * SAF export can report an accurate byte count for logging and the success
 * snackbar without re-reading the destination document.
 *
 * It does NOT close the delegate on [close] — the SAF caller owns the lifecycle of
 * the underlying stream.
 */
private class CountingOutputStream(
    delegate: OutputStream,
) : FilterOutputStream(delegate) {
    var bytesWritten: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        bytesWritten += 1
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        // Bypass FilterOutputStream's byte-at-a-time default for throughput.
        out.write(b, off, len)
        bytesWritten += len
    }

    override fun close() {
        // Flush only; the caller closes the underlying SAF stream.
        out.flush()
    }
}
