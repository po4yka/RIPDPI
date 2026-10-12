package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
import com.poyka.ripdpi.diagnostics.dpi.AttemptResult
import com.poyka.ripdpi.diagnostics.dpi.AttemptStatus
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpich.CompressionProbeVerdict
import com.poyka.ripdpi.platform.StringResolver

internal fun DomainVerdict.displayLabel(strings: StringResolver): String =
    strings.getString(
        when (this) {
            DomainVerdict.OK -> R.string.diagnostics_probe_ok
            DomainVerdict.BLOCKED -> R.string.diagnostics_dpi_suite_blocked
            DomainVerdict.TLS_VERSION_BLOCK -> R.string.diagnostics_probe_tls_version_block
            DomainVerdict.ISP_PAGE -> R.string.diagnostics_probe_isp_page
            DomainVerdict.TCP16_BAND -> R.string.diagnostics_probe_tcp16_band
            DomainVerdict.DNS_FAIL -> R.string.diagnostics_error_dns_failure
            DomainVerdict.UNREACHABLE -> R.string.diagnostics_probe_unreachable
            DomainVerdict.FAKE_IP -> R.string.diagnostics_probe_fake_ip
        },
    )

internal fun CompressionProbeVerdict.displayLabel(strings: StringResolver): String =
    strings.getString(
        when (this) {
            CompressionProbeVerdict.OK -> R.string.diagnostics_probe_ok
            CompressionProbeVerdict.NOT_SUPPORTED -> R.string.diagnostics_compression_not_supported
            CompressionProbeVerdict.EOF_BEFORE_MIN -> R.string.diagnostics_compression_eof_before_min
            CompressionProbeVerdict.TIMEOUT -> R.string.diagnostics_value_timeout
            CompressionProbeVerdict.CONN_ERR -> R.string.diagnostics_compression_connection_error
            CompressionProbeVerdict.INTERNAL_ERR -> R.string.diagnostics_compression_internal_error
        },
    )

internal fun AttemptResult.displayLabel(strings: StringResolver): String =
    buildList {
        add(status.displayLabel(strings))
        statusCode?.let { code -> add(code.toString()) }
        error?.let { error -> add(error.displayLabel(strings)) }
    }.joinToString(" · ")

private fun AttemptStatus.displayLabel(strings: StringResolver): String =
    strings.getString(
        when (this) {
            AttemptStatus.OK -> R.string.diagnostics_probe_ok
            AttemptStatus.BLOCKED -> R.string.diagnostics_dpi_suite_blocked
            AttemptStatus.REDIR_OK -> R.string.diagnostics_probe_redirect_ok
            AttemptStatus.REDIR_SUSPICIOUS -> R.string.diagnostics_probe_redirect_suspicious
            AttemptStatus.ISP_PAGE -> R.string.diagnostics_probe_isp_page
            AttemptStatus.FAKE_IP -> R.string.diagnostics_probe_fake_ip
            AttemptStatus.TCP16_BAND_TIMEOUT -> R.string.diagnostics_probe_tcp16_band_timeout
            AttemptStatus.ERROR -> R.string.diagnostics_probe_error
        },
    )

private fun DpiProbeError.displayLabel(strings: StringResolver): String =
    strings.getString(
        when (this) {
            DpiProbeError.TlsSpoof -> R.string.diagnostics_probe_error_tls_spoof
            DpiProbeError.TlsAlertSni -> R.string.diagnostics_probe_error_tls_alert_sni
            DpiProbeError.TlsAlertHandshake -> R.string.diagnostics_probe_error_tls_alert_handshake
            DpiProbeError.TlsBlockVersion -> R.string.diagnostics_probe_tls_version_block
            DpiProbeError.TlsRst -> R.string.diagnostics_probe_error_tls_rst
            DpiProbeError.TlsEof -> R.string.diagnostics_probe_error_tls_eof
            DpiProbeError.TlsDrop -> R.string.diagnostics_probe_error_tls_drop
            DpiProbeError.TlsMitm -> R.string.diagnostics_probe_error_tls_mitm
            DpiProbeError.SynDrop -> R.string.diagnostics_probe_error_syn_drop
            DpiProbeError.TcpRst -> R.string.diagnostics_probe_error_tcp_rst
            DpiProbeError.TlsRstTls -> R.string.diagnostics_probe_error_tls_rst_tls
            DpiProbeError.TcpAbort -> R.string.diagnostics_probe_error_tcp_abort
            DpiProbeError.DnsFail -> R.string.diagnostics_error_dns_failure
            DpiProbeError.Refused -> R.string.diagnostics_probe_error_refused
            DpiProbeError.NetUnreach -> R.string.diagnostics_probe_error_net_unreach
            DpiProbeError.HostUnreach -> R.string.diagnostics_probe_error_host_unreach
            DpiProbeError.Unknown -> R.string.diagnostics_probe_error_unknown
        },
    )
