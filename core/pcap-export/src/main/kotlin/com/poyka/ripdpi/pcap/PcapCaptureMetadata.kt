package com.poyka.ripdpi.pcap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON shape returned by the Rust pcap_stop / pcap_list_captures
 * entry functions in native/rust/crates/ripdpi-tunnel-android/src/pcap.rs.
 *
 * Mirrors `#[serde(rename_all = "camelCase")] struct PcapCaptureMetadata`.
 * Field renames are explicit here for resilience against future Kotlin
 * naming convention drift; do NOT rely on default camelCase translation.
 */
@Serializable
data class PcapCaptureMetadata(
    val path: String,
    @SerialName("byteSize") val byteSize: Long,
    @SerialName("packetCount") val packetCount: Long,
    @SerialName("startedAtMs") val startedAtMs: Long,
    @SerialName("endedAtMs") val endedAtMs: Long,
    val drops: Long,
)

/**
 * Terminal native writer state returned when a capture is stopped.
 *
 * [files] includes only PCAP files that were completely flushed, synced and
 * atomically renamed by native code. [failure] is a stable writer code rather
 * than a filesystem error, so it is safe to surface in local diagnostics.
 */
@Serializable
data class PcapStopResult(
    @SerialName("wasActive") val wasActive: Boolean,
    val files: List<PcapCaptureMetadata>,
    @SerialName("totalDrops") val totalDrops: Long = 0L,
    val failure: String? = null,
)

/** Path-free terminal evidence; raw PCAP remains a separate user artifact. */
@Serializable
data class PcapCaptureCompletionReceipt(
    @SerialName("captureSetId") val captureSetId: Long,
    @SerialName("totalDrops") val totalDrops: Long?,
    val outcome: PcapCaptureOutcome,
)

@Serializable
enum class PcapCaptureOutcome {
    @SerialName("captured_separate")
    CapturedSeparate,

    @SerialName("failed")
    Failed,
}
