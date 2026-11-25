package com.poyka.ripdpi.pcap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PcapCaptureMetadata(
    val path: String,
    @SerialName("byteSize") val byteSize: Long,
    @SerialName("packetCount") val packetCount: Long,
    @SerialName("startedAtMs") val startedAtMs: Long,
    @SerialName("endedAtMs") val endedAtMs: Long,
    val drops: Long,
)

@Serializable
data class PcapStopResult(
    @SerialName("wasActive") val wasActive: Boolean,
    val files: List<PcapCaptureMetadata>,
    @SerialName("totalDrops") val totalDrops: Long = 0L,
    val failure: String? = null,
    val pending: Boolean = false,
    @SerialName("limitReached") val limitReached: Boolean = false,
)

@Serializable
data class PcapCaptureCompletionReceipt(
    @SerialName("captureSetId") val captureSetId: Long,
    @SerialName("totalDrops") val totalDrops: Long?,
    val outcome: PcapCaptureOutcome,
    @SerialName("limitReached") val limitReached: Boolean = false,
)

@Serializable
enum class PcapCaptureOutcome {
    @SerialName("captured_separate")
    CapturedSeparate,

    @SerialName("failed")
    Failed,
}
