package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.Http3DnsStatus
import com.poyka.ripdpi.diagnostics.Http3ProbeStage
import com.poyka.ripdpi.diagnostics.Http3ProbeStatus

internal fun Http3ProbeStatus.http3LabelResource(): Int =
    when (this) {
        Http3ProbeStatus.COMPLETE -> R.string.diagnostics_http3_success
        Http3ProbeStatus.HTTP_ERROR -> R.string.diagnostics_http3_http_error
        Http3ProbeStatus.BODY_LIMIT -> R.string.diagnostics_http3_body_limit
        Http3ProbeStatus.FAILED -> R.string.diagnostics_ip_failed
        Http3ProbeStatus.TIMEOUT -> R.string.diagnostics_ip_timeout
        Http3ProbeStatus.CANCELLED -> R.string.diagnostics_ip_cancelled
        Http3ProbeStatus.UNSUPPORTED -> R.string.diagnostics_ip_unsupported
        Http3ProbeStatus.INVALID -> R.string.diagnostics_ip_invalid
        Http3ProbeStatus.NOT_OBSERVED -> R.string.diagnostics_ip_missing
    }

internal fun Http3ProbeStage.http3LabelResource(): Int =
    when (this) {
        Http3ProbeStage.DNS -> R.string.diagnostics_http3_dns
        Http3ProbeStage.QUIC_HANDSHAKE -> R.string.diagnostics_http3_handshake
        Http3ProbeStage.HTTP_REQUEST -> R.string.diagnostics_http3_request
        Http3ProbeStage.HTTP_HEADERS -> R.string.diagnostics_http3_headers
        Http3ProbeStage.HTTP_BODY -> R.string.diagnostics_http3_body
    }

internal fun Http3DnsStatus.http3LabelResource(): Int =
    when (this) {
        Http3DnsStatus.NOT_RUN -> R.string.diagnostics_http3_not_observed
        Http3DnsStatus.PINNED -> R.string.diagnostics_http3_pinned
        Http3DnsStatus.RESOLVED -> R.string.diagnostics_http3_resolved
        Http3DnsStatus.FAILED -> R.string.diagnostics_ip_failed
        Http3DnsStatus.TIMEOUT -> R.string.diagnostics_ip_timeout
    }

internal fun http3ReasonResource(reason: String?): Int =
    when (reason) {
        "dns_error" -> R.string.diagnostics_ip_dns_error
        "dns_timeout", "timeout", "deadline_exceeded" -> R.string.diagnostics_ip_timeout
        "no_addresses" -> R.string.diagnostics_http3_no_addresses
        "connect_error" -> R.string.diagnostics_http3_connect_error
        "tls_error" -> R.string.diagnostics_http3_tls_error
        "alpn_mismatch" -> R.string.diagnostics_http3_alpn_error
        "http3_error" -> R.string.diagnostics_http3_protocol_error
        "http_status_error" -> R.string.diagnostics_http3_http_error
        "body_limit" -> R.string.diagnostics_http3_body_limit
        "cancelled" -> R.string.diagnostics_ip_cancelled
        "unsupported_path" -> R.string.diagnostics_ip_proxy
        "invalid_config" -> R.string.diagnostics_ip_invalid_config
        "protect_failed" -> R.string.diagnostics_http3_protect_error
        "network_scope_unverified" -> R.string.diagnostics_ip_network_changed
        "io_error" -> R.string.diagnostics_ip_io
        else -> R.string.diagnostics_ip_unknown
    }
