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
