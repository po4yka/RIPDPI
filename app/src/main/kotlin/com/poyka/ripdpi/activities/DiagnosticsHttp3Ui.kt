package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.Http3PathScope
import com.poyka.ripdpi.diagnostics.Http3ProbeEvidence
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.parseHttp3ProbeEvidence
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toImmutableList

internal fun ProbeResult.toHttp3Group(
    strings: StringResolver,
    networkScopeUnverified: Boolean = false,
): DiagnosticsContextGroupUiModel? =
    if (probeType == "http3") {
        http3Group(strings, parseHttp3ProbeEvidence(details), networkScopeUnverified)
    } else {
        null
    }

internal fun http3Group(
    strings: StringResolver,
    evidence: Http3ProbeEvidence?,
    networkScopeUnverified: Boolean = false,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = strings.getString(R.string.diagnostics_http3_title),
        fields = Http3Fields(strings).fields(evidence, networkScopeUnverified).toImmutableList(),
        stackedFields = true,
    )

private class Http3Fields(
    private val strings: StringResolver,
) {
    private val unknown get() = strings.getString(R.string.diagnostics_ip_unknown)

    fun fields(
        evidence: Http3ProbeEvidence?,
        networkScopeUnverified: Boolean,
    ): List<DiagnosticsFieldUiModel> =
        buildList {
            if (evidence == null) {
                add(field(R.string.diagnostics_ip_status, strings.getString(R.string.diagnostics_ip_missing)))
            } else {
                addAll(observation(evidence))
                addAll(timings(evidence))
            }
            if (networkScopeUnverified || evidence?.reason == "network_scope_unverified") {
                add(field(R.string.diagnostics_ip_scope, strings.getString(R.string.diagnostics_ip_network_changed)))
            }
            add(field(R.string.diagnostics_ip_measurement, strings.getString(R.string.diagnostics_http3_caution)))
        }

    private fun observation(e: Http3ProbeEvidence): List<DiagnosticsFieldUiModel> =
        listOf(
            field(R.string.diagnostics_ip_status, strings.getString(e.status.http3LabelResource())),
            field(R.string.diagnostics_http3_validated, observed(e.http3Validated)),
            field(R.string.diagnostics_http3_tls, observed(e.tlsValidated)),
            field(R.string.diagnostics_http3_sent, observed(e.requestSent)),
            field(R.string.diagnostics_http3_complete, observed(e.bodyComplete)),
            field(R.string.diagnostics_ip_stage, strings.getString(e.stage.http3LabelResource())),
            field(R.string.diagnostics_http3_dns, strings.getString(e.dnsStatus.http3LabelResource())),
            field(R.string.diagnostics_http3_status_code, e.httpStatus?.let { number(it.toLong()) } ?: unknown),
            field(R.string.diagnostics_http3_bytes, number(e.responseBytes)),
            field(R.string.diagnostics_ip_reason, strings.getString(http3ReasonResource(e.reason))),
            field(R.string.diagnostics_http3_attempts, number(e.attemptCount.toLong())),
            field(
                R.string.diagnostics_ip_scope,
                strings.getString(
                    if (e.pathScope ==
                        Http3PathScope.RAW_PATH
                    ) {
                        R.string.diagnostics_ip_raw
                    } else {
                        R.string.diagnostics_ip_proxy
                    },
                ),
            ),
        )

    private fun timings(e: Http3ProbeEvidence): List<DiagnosticsFieldUiModel> =
        listOf(
            field(R.string.diagnostics_http3_elapsed, time(e.durationMs)),
            field(R.string.diagnostics_http3_handshake_time, time(e.handshakeElapsedMs)),
            field(R.string.diagnostics_http3_headers_time, time(e.headersElapsedMs)),
            field(R.string.diagnostics_http3_first_byte_time, time(e.firstByteElapsedMs)),
        )

    private fun observed(value: Boolean): String =
        strings.getString(
            if (value) R.string.diagnostics_http3_observed else R.string.diagnostics_http3_not_observed,
        )

    private fun number(value: Long): String = strings.getString(R.string.diagnostics_http3_number, value)

    private fun time(value: Long?): String =
        value?.let { strings.getString(R.string.diagnostics_http3_milliseconds, it) } ?: unknown

    private fun field(
        label: Int,
        value: String,
    ) = DiagnosticsFieldUiModel(strings.getString(label), value)
}
