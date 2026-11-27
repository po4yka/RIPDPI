package com.poyka.ripdpi.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** Explicit, bounded controls for payload-qualified automatic selector changes. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PayloadFailoverPolicy(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("selector_tag") val selectorTag: String,
    val probes: List<PayloadProbeControl>,
    @SerialName("interval_seconds") val intervalSeconds: Int,
    @SerialName("timeout_seconds") val timeoutSeconds: Int,
    @SerialName("failure_threshold") val failureThreshold: Int,
    @SerialName("recovery_threshold") val recoveryThreshold: Int,
    @SerialName("cooldown_seconds") val cooldownSeconds: Int,
    @SerialName("max_backoff_seconds") val maxBackoffSeconds: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val quality: ProfileQualityPolicy? = null,
) {
    init {
        require(schemaVersion == 2 && selectorTag == "select")
        require(probes.size == 2 && probes.map { it.origin() }.distinct().size == probes.size)
        require(intervalSeconds in MinIntervalSeconds..MaxTimingSeconds)
        require(timeoutSeconds in 1..MaxTimeoutSeconds && timeoutSeconds <= intervalSeconds)
        require(failureThreshold in MinThreshold..MaxThreshold && recoveryThreshold in MinThreshold..MaxThreshold)
        require(cooldownSeconds in MinIntervalSeconds..MaxTimingSeconds)
        require(maxBackoffSeconds in MinBackoffSeconds..MaxTimingSeconds && maxBackoffSeconds >= intervalSeconds)
        quality?.let {
            require(it.maxPassBytes >= reservedQualityPassBytes())
            require(it.windowSeconds >= intervalSeconds)
        }
    }

    fun reservedQualityPassBytes(): Long = 2L * probes.sumOf { it.expectedBytes.toLong() + 1L }

    override fun toString() = "PayloadFailoverPolicy([REDACTED])"
}

@Serializable
data class PayloadProbeControl(
    val url: String,
    @SerialName("expected_bytes") val expectedBytes: Int,
    val sha256: String,
) {
    init {
        require(expectedBytes in MinPayloadBytes..MaxPayloadBytes)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        origin()
    }

    internal fun origin(): Pair<String, Int> {
        require(url.length <= MaxUrlLength && url.startsWith("https://"))
        require(url.all { it.code in MinUrlCharacter..MaxUrlCharacter && it != '\\' })
        val uri = URI.create(url)
        val rawHost = requireNotNull(uri.host).lowercase().trimEnd('.')
        require(rawHost.isNotEmpty() && '%' !in rawHost && uri.rawUserInfo == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..MaxPort)
        val host = requireNotNull(url.toHttpUrlOrNull()).host.trimEnd('.')
        if (':' !in host) {
            require(rawHost.length <= MaxDnsHostLength)
            val labels = rawHost.split('.')
            require(labels.all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) })
            if (labels.last().all { it.isDigit() } || labels.last().matches(Regex("0x[0-9a-f]+"))) {
                require(
                    labels.size == Ipv4Octets &&
                        labels.all { label ->
                            label.toIntOrNull()?.let { it in 0..MaxIpv4Octet && it.toString() == label } == true
                        },
                )
            }
        }
        return host to if (uri.port == -1) HttpsPort else uri.port
    }

    override fun toString() = "PayloadProbeControl([REDACTED])"
}

private const val MinIntervalSeconds = 30
private const val MaxTimingSeconds = 3_600
private const val MinBackoffSeconds = 60
private const val MaxTimeoutSeconds = 30
private const val MinThreshold = 2
private const val MaxThreshold = 10
private const val MinPayloadBytes = 32_768
private const val MaxPayloadBytes = 1_048_576
private const val MaxUrlLength = 2_048
private const val MinUrlCharacter = 33
private const val MaxUrlCharacter = 126
private const val MaxPort = 65_535
private const val HttpsPort = 443

private const val MaxDnsHostLength = 253
private const val Ipv4Octets = 4
private const val MaxIpv4Octet = 255
