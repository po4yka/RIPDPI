package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

const val Http3ProfileId = "http3-connectivity"

@Serializable
data class Http3ProbeConfig(
    val version: Int = 1,
    val host: String = "www.cloudflare.com",
    val port: Int = 443,
    val path: String = "/cdn-cgi/trace",
    val connectIp: String? = null,
    val timeoutMs: Long = 5_000,
    val maxResponseBytes: Long = 65_536,
) {
    internal fun validate() {
        require(version == 1 && port in 1..IpProbeMaxPort)
        require(timeoutMs in Http3MinTimeoutMs..Http3MaxTimeoutMs && maxResponseBytes in 1..Http3MaxResponseBytes)
        require(isHttp3Host(host))
        require(connectIp == null || isIpProbeV4(connectIp) || isIpProbeV6(connectIp))
        require(path.length in 1..Http3MaxPathLength && path.startsWith('/') && !path.startsWith("//"))
        require(path.all { it in '!'..'~' && it !in "?#\\" })
    }
}

@Serializable
enum class Http3ProbeStage { DNS, QUIC_HANDSHAKE, HTTP_REQUEST, HTTP_HEADERS, HTTP_BODY }

@Serializable
enum class Http3ProbeStatus {
    COMPLETE,
    HTTP_ERROR,
    BODY_LIMIT,
    FAILED,
    TIMEOUT,
    CANCELLED,
    UNSUPPORTED,
    INVALID,
    NOT_OBSERVED,
}

@Serializable
enum class Http3DnsStatus { NOT_RUN, PINNED, RESOLVED, FAILED, TIMEOUT }

@Serializable
enum class Http3PathScope { RAW_PATH, UNSUPPORTED_PROXY }

@Serializable
data class Http3ProbeEvidence(
    val version: Int = 1,
    val stage: Http3ProbeStage,
    val status: Http3ProbeStatus,
    val dnsStatus: Http3DnsStatus = Http3DnsStatus.NOT_RUN,
    val reason: String? = null,
    val peerAddress: String? = null,
    val alpn: String? = null,
    val tlsValidated: Boolean = false,
    val http3Validated: Boolean = false,
    val requestSent: Boolean = false,
    val httpStatus: Int? = null,
    val responseBytes: Long = 0,
    val bodyComplete: Boolean = false,
    val attemptCount: Int = 0,
    val durationMs: Long? = null,
    val handshakeElapsedMs: Long? = null,
    val headersElapsedMs: Long? = null,
    val firstByteElapsedMs: Long? = null,
    val pathScope: Http3PathScope = Http3PathScope.RAW_PATH,
)

internal fun isHttp3Host(host: String): Boolean =
    isIpProbeV4(host) || isIpProbeV6(host) ||
        (
            host.length in 1..Http3MaxHostLength &&
                host.split('.').all { label ->
                    label.length in 1..Http3MaxLabelLength && label.first() != '-' && label.last() != '-' &&
                        label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
                }
        )

private const val Http3MinTimeoutMs = 250L
private const val Http3MaxTimeoutMs = 15_000L
internal const val Http3MaxResponseBytes = 262_144L
private const val Http3MaxPathLength = 1_024
private const val Http3MaxHostLength = 253
private const val Http3MaxLabelLength = 63
