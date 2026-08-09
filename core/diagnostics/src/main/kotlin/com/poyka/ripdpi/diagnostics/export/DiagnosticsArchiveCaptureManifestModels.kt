package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveCaptureManifestPayload(
    val captureManifestVersion: String = "capture_manifest_v1",
    val archiveSchemaVersion: Int,
    val hashAlgorithm: String = "sha256",
    val rawArtifactsEmbedded: Boolean = false,
    val discoveryDisposition: DiagnosticsArchiveCaptureDisposition,
    val discoveryScope: String = "recent_completed_packet_captures",
    val captureFileLimit: Int,
    val captures: List<DiagnosticsArchiveCaptureEntry>,
)

@Serializable
internal data class DiagnosticsArchiveCaptureEntry(
    val captureId: String,
    val disposition: DiagnosticsArchiveCaptureDisposition,
    val sha256: String? = null,
    val fileByteCount: Long,
    val captureWindow: DiagnosticsArchiveCaptureWindow,
    val packetCount: Long,
    val capturedPacketBytes: Long,
    val originalPacketBytes: Long,
    val snaplen: Long? = null,
    val linkType: Long? = null,
    val truncated: Boolean,
    val truncationReasons: List<String> = emptyList(),
    val stageIds: List<String> = emptyList(),
    val attemptIds: List<String> = emptyList(),
    val associationWarnings: List<String> = emptyList(),
    val completenessWarnings: List<String> = emptyList(),
    val rawArtifactEmbedded: Boolean = false,
    val rawFileNamePresent: Boolean = false,
    val parseFailureReason: String? = null,
)

@Serializable
internal data class DiagnosticsArchiveCaptureWindow(
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val timestampBasis: String = "unix_epoch_ms",
)

@Serializable
internal enum class DiagnosticsArchiveCaptureDisposition {
    @SerialName("captured")
    CAPTURED,

    @SerialName("empty")
    EMPTY,

    @SerialName("invalid")
    INVALID,
}
