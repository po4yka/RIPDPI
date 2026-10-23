package com.poyka.ripdpi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileQualityPolicy(
    @SerialName("max_pass_bytes") val maxPassBytes: Long,
    @SerialName("max_window_bytes") val maxWindowBytes: Long,
    @SerialName("window_seconds") val windowSeconds: Int,
    @SerialName("minimum_gain_percent") val minimumGainPercent: Int,
    @SerialName("maximum_loaded_latency_increase_ms") val maximumLoadedLatencyIncreaseMs: Int,
) {
    init {
        require(maxPassBytes in MinAllowance..MaxPassAllowance)
        require(maxWindowBytes in maxPassBytes..MaxWindowAllowance)
        require(windowSeconds in MinWindowSeconds..MaxQualityWindowSeconds)
        require(minimumGainPercent in MinGainPercent..MaxGainPercent)
        require(maximumLoadedLatencyIncreaseMs in 0..MaxLoadedIncreaseMillis)
    }
}

internal const val MaxQualityWindowSeconds = 86_400
private const val MinAllowance = 65_538L
private const val MaxPassAllowance = 16_777_216L
private const val MaxWindowAllowance = 268_435_456L
private const val MinWindowSeconds = 60
private const val MinGainPercent = 5
private const val MaxGainPercent = 100
private const val MaxLoadedIncreaseMillis = 2_000
