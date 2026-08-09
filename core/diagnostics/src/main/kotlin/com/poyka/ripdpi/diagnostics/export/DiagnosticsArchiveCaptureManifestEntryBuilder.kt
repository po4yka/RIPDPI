package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal class DiagnosticsArchiveCaptureManifestEntryBuilder(
    private val json: Json,
) {
    fun build(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveEntry {
        val captures =
            selection.captureFiles
                .sortedWith(compareBy(File::lastModified, File::getName))
                .mapIndexed { index, file -> buildCapture(index, file, selection) }
        val payload =
            DiagnosticsArchiveCaptureManifestPayload(
                archiveSchemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                discoveryDisposition =
                    when {
                        captures.isEmpty() -> {
                            DiagnosticsArchiveCaptureDisposition.EMPTY
                        }

                        captures.all { it.disposition == DiagnosticsArchiveCaptureDisposition.INVALID } -> {
                            DiagnosticsArchiveCaptureDisposition.INVALID
                        }

                        else -> {
                            DiagnosticsArchiveCaptureDisposition.CAPTURED
                        }
                    },
                captureFileLimit = DiagnosticsArchiveFormat.captureFileLimit,
                captures = captures,
            )
        return DiagnosticsArchiveEntry(
            name = "capture-manifest.json",
            bytes = json.encodeToString(DiagnosticsArchiveCaptureManifestPayload.serializer(), payload).toByteArray(),
        )
    }

    private fun buildCapture(
        index: Int,
        file: File,
        selection: DiagnosticsArchiveSelection,
    ): DiagnosticsArchiveCaptureEntry {
        val digest = runCatching(file::sha256).getOrNull()
        val parsed = runCatching(file::parsePcap).getOrElse { error -> ParsedPcap.invalid(error) }
        val stageIds = selection.stageIdsOverlapping(parsed.captureWindow)
        val attemptAssociation = selection.attemptIdsOverlapping(parsed.captureWindow)
        val associationWarnings =
            buildList {
                if (parsed.captureWindow.startedAt == null || parsed.captureWindow.finishedAt == null) {
                    add("packet_timestamps_unavailable")
                }
                if (selection.compositeStages.isNotEmpty() && stageIds.isEmpty()) {
                    add("stage_overlap_unavailable")
                }
                addAll(attemptAssociation.warnings)
            }
        val disposition =
            when {
                parsed.failureReason != null || digest == null -> DiagnosticsArchiveCaptureDisposition.INVALID
                parsed.packetCount == 0L -> DiagnosticsArchiveCaptureDisposition.EMPTY
                else -> DiagnosticsArchiveCaptureDisposition.CAPTURED
            }
        return DiagnosticsArchiveCaptureEntry(
            captureId = "capture-${index + 1}",
            disposition = disposition,
            sha256 = digest,
            fileByteCount = file.length(),
            captureWindow = parsed.captureWindow,
            packetCount = parsed.packetCount,
            capturedPacketBytes = parsed.capturedPacketBytes,
            originalPacketBytes = parsed.originalPacketBytes,
            snaplen = parsed.snaplen,
            linkType = parsed.linkType,
            truncated = parsed.truncated,
            truncationReasons = if (parsed.truncated) listOf("packet_snaplen_truncation") else emptyList(),
            stageIds = stageIds,
            attemptIds = attemptAssociation.ids,
            associationWarnings = associationWarnings,
            completenessWarnings = listOf("drop_count_unavailable", "capture_limit_status_unavailable"),
            parseFailureReason = parsed.failureReason ?: if (digest == null) "capture_hash_unavailable" else null,
        )
    }
}

private data class ParsedPcap(
    val captureWindow: DiagnosticsArchiveCaptureWindow = DiagnosticsArchiveCaptureWindow(),
    val packetCount: Long = 0,
    val capturedPacketBytes: Long = 0,
    val originalPacketBytes: Long = 0,
    val snaplen: Long? = null,
    val linkType: Long? = null,
    val truncated: Boolean = false,
    val failureReason: String? = null,
) {
    companion object {
        fun invalid(error: Throwable): ParsedPcap =
            ParsedPcap(
                failureReason =
                    when (error) {
                        is PcapParseException -> error.code
                        else -> "capture_read_failed"
                    },
            )
    }
}

private class PcapParseException(
    val code: String,
) : Exception(code)

private fun File.parsePcap(): ParsedPcap =
    inputStream().buffered().use { input ->
        val globalHeader = input.readUpTo(PcapGlobalHeaderBytes)
        if (globalHeader.size != PcapGlobalHeaderBytes) throw PcapParseException("pcap_global_header_incomplete")
        val format = globalHeader.pcapFormat()
        val header = ByteBuffer.wrap(globalHeader).order(format.byteOrder)
        header.position(PcapVersionOffset)
        val major = header.short.toInt() and UnsignedShortMask
        val minor = header.short.toInt() and UnsignedShortMask
        if (major != PcapVersionMajor || minor != PcapVersionMinor) {
            throw PcapParseException("pcap_version_unsupported")
        }
        header.position(PcapSnaplenOffset)
        val snaplen = header.unsignedInt()
        val linkType = header.unsignedInt()
        var packetCount = 0L
        var capturedPacketBytes = 0L
        var originalPacketBytes = 0L
        var firstTimestampMs: Long? = null
        var lastTimestampMs: Long? = null
        var truncated = false
        while (true) {
            val packetHeader = input.readUpTo(PcapPacketHeaderBytes)
            if (packetHeader.isEmpty()) break
            if (packetHeader.size != PcapPacketHeaderBytes) throw PcapParseException("pcap_packet_header_incomplete")
            val packet = ByteBuffer.wrap(packetHeader).order(format.byteOrder)
            val seconds = packet.unsignedInt()
            val fraction = packet.unsignedInt()
            val capturedLength = packet.unsignedInt()
            val originalLength = packet.unsignedInt()
            if (capturedLength > snaplen) throw PcapParseException("pcap_packet_exceeds_snaplen")
            if (!input.skipExactly(capturedLength)) {
                throw PcapParseException("pcap_packet_payload_incomplete")
            }
            val timestampMs = seconds * MillisecondsPerSecond + fraction / format.fractionUnitsPerMillisecond
            firstTimestampMs = firstTimestampMs?.let { minOf(it, timestampMs) } ?: timestampMs
            lastTimestampMs = lastTimestampMs?.let { maxOf(it, timestampMs) } ?: timestampMs
            packetCount += 1
            capturedPacketBytes += capturedLength
            originalPacketBytes += originalLength
            truncated = truncated || capturedLength < originalLength
        }
        ParsedPcap(
            captureWindow = DiagnosticsArchiveCaptureWindow(firstTimestampMs, lastTimestampMs),
            packetCount = packetCount,
            capturedPacketBytes = capturedPacketBytes,
            originalPacketBytes = originalPacketBytes,
            snaplen = snaplen,
            linkType = linkType,
            truncated = truncated,
        )
    }

private fun InputStream.readUpTo(byteCount: Int): ByteArray {
    val bytes = ByteArray(byteCount)
    var offset = 0
    while (offset < byteCount) {
        val read = read(bytes, offset, byteCount - offset)
        if (read < 0) return bytes.copyOf(offset)
        offset += read
    }
    return bytes
}

private fun InputStream.skipExactly(byteCount: Long): Boolean {
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() < 0) {
            return false
        } else {
            remaining -= 1
        }
    }
    return true
}

private data class PcapFormat(
    val byteOrder: ByteOrder,
    val fractionUnitsPerMillisecond: Long,
)

private fun ByteArray.pcapFormat(): PcapFormat =
    when {
        startsWithBytes(
            PcapLittleEndianMicrosecondMagic,
        ) -> PcapFormat(ByteOrder.LITTLE_ENDIAN, MicrosecondsPerMillisecond)

        startsWithBytes(PcapBigEndianMicrosecondMagic) -> PcapFormat(ByteOrder.BIG_ENDIAN, MicrosecondsPerMillisecond)

        startsWithBytes(
            PcapLittleEndianNanosecondMagic,
        ) -> PcapFormat(ByteOrder.LITTLE_ENDIAN, NanosecondsPerMillisecond)

        startsWithBytes(PcapBigEndianNanosecondMagic) -> PcapFormat(ByteOrder.BIG_ENDIAN, NanosecondsPerMillisecond)

        else -> throw PcapParseException("pcap_magic_unsupported")
    }

private fun ByteArray.startsWithBytes(expected: IntArray): Boolean =
    expected.indices.all { index -> this[index].toInt() and ByteMask == expected[index] }

private fun ByteBuffer.unsignedInt(): Long = int.toLong() and UnsignedIntMask

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(HashBufferBytes)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun DiagnosticsArchiveSelection.stageIdsOverlapping(
    captureWindow: DiagnosticsArchiveCaptureWindow,
): List<String> =
    compositeStages
        .filter { stage -> stage.session?.captureWindow()?.overlaps(captureWindow) == true }
        .map { stage -> stage.stageSummary.stageKey }

private data class AttemptAssociation(
    val ids: List<String>,
    val warnings: List<String>,
)

private fun DiagnosticsArchiveSelection.attemptIdsOverlapping(
    captureWindow: DiagnosticsArchiveCaptureWindow,
): AttemptAssociation {
    val attemptWindows =
        buildList {
            addAll(primaryReport.attemptWindows("root"))
            compositeStages.forEach { stage ->
                addAll(stage.report.attemptWindows("stage:${stage.stageSummary.stageKey}"))
            }
        }
    if (attemptWindows.isEmpty()) {
        return AttemptAssociation(emptyList(), listOf("attempt_timestamps_unavailable"))
    }
    return AttemptAssociation(
        ids = attemptWindows.filter { it.window.overlaps(captureWindow) }.map(AttemptWindow::id),
        warnings = emptyList(),
    )
}

private data class AttemptWindow(
    val id: String,
    val window: DiagnosticsArchiveCaptureWindow,
)

private fun com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire?.attemptWindows(
    scopeId: String,
): List<AttemptWindow> =
    this
        ?.strategyProbeReport
        ?.attempts
        .orEmpty()
        .mapNotNull { attempt ->
            val startedAt = attempt.startedAtMs ?: return@mapNotNull null
            AttemptWindow(
                id = "$scopeId:attempt-${attempt.sequence}",
                window = DiagnosticsArchiveCaptureWindow(startedAt, startedAt + (attempt.durationMs ?: 0L)),
            )
        }

private fun com.poyka.ripdpi.data.diagnostics.ScanSessionEntity.captureWindow(): DiagnosticsArchiveCaptureWindow =
    DiagnosticsArchiveCaptureWindow(startedAt, finishedAt ?: startedAt)

private fun DiagnosticsArchiveCaptureWindow.overlaps(other: DiagnosticsArchiveCaptureWindow): Boolean {
    val leftStart = startedAt
    val leftEnd = finishedAt
    val rightStart = other.startedAt
    val rightEnd = other.finishedAt
    return leftStart != null &&
        leftEnd != null &&
        rightStart != null &&
        rightEnd != null &&
        leftStart <= rightEnd &&
        rightStart <= leftEnd
}

private const val PcapGlobalHeaderBytes = 24
private const val PcapPacketHeaderBytes = 16
private const val PcapVersionMajor = 2
private const val PcapVersionMinor = 4
private const val PcapVersionOffset = 4
private const val PcapSnaplenOffset = 16
private const val HashBufferBytes = 64 * 1024
private const val UnsignedIntMask = 0xffff_ffffL
private const val UnsignedShortMask = 0xffff
private const val ByteMask = 0xff
private const val MillisecondsPerSecond = 1_000L
private const val MicrosecondsPerMillisecond = 1_000L
private const val NanosecondsPerMillisecond = 1_000_000L
private val PcapLittleEndianMicrosecondMagic = intArrayOf(0xd4, 0xc3, 0xb2, 0xa1)
private val PcapBigEndianMicrosecondMagic = intArrayOf(0xa1, 0xb2, 0xc3, 0xd4)
private val PcapLittleEndianNanosecondMagic = intArrayOf(0x4d, 0x3c, 0xb2, 0xa1)
private val PcapBigEndianNanosecondMagic = intArrayOf(0xa1, 0xb2, 0x3c, 0x4d)
