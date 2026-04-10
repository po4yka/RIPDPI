package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PreferredEdgeTransportQuic
import com.poyka.ripdpi.data.PreferredEdgeTransportTcp

internal fun ProbeResult.detailValue(key: String): String? = details.firstOrNull { it.key == key }?.value

internal fun ProbeResult.inferEdgeHost(): String? =
    target
        .substringAfterLast(
            " · ",
            missingDelimiterValue = target,
        ).substringBefore(' ')
        .trim()
        .takeIf { it.isNotEmpty() }

internal fun ProbeResult.edgeTransportKind(): String? =
    when (probeType) {
        "strategy_quic" -> {
            PreferredEdgeTransportQuic
        }

        "strategy_http", "strategy_https", "service_handshake", "service_gateway", "throughput" -> {
            PreferredEdgeTransportTcp
        }

        else -> {
            null
        }
    }

internal fun ProbeResult.edgeSuccess(): Boolean =
    when (probeType) {
        "strategy_quic" -> outcome == "quic_initial_response" || outcome == "quic_response"
        "strategy_http" -> outcome == "http_ok" || outcome == "http_redirect"
        "strategy_https" -> outcome == "tls_ok" || outcome == "tls_version_split" || outcome == "tls_ech_only"
        "service_handshake", "service_gateway", "throughput" -> !outcome.contains("unreachable", ignoreCase = true)
        else -> false
    }

internal fun ProbeResult.edgeEchCapable(): Boolean =
    detailValue("echCapable") == "true" || detailValue("tlsEchResolutionDetail") == "ech_config_available"
