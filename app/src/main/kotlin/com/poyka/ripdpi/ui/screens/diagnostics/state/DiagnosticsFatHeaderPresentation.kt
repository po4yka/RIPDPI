package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal fun ProbeResult.toFatHeaderEvidence(strings: StringResolver): ImmutableList<String> {
    if (probeType != "tcp_fat_header") return persistentListOf()
    val fields = details.associate { it.key to it.value }
    val planned = fields.count("requestsPlanned")
    val written = fields.count("requestsWritten")
    val responses = fields.count("responsesSeen")
    val covered = fields["thresholdCovered"]?.toBooleanStrictOrNull()
    val hasSeriesCounts = planned != null && written != null && responses != null
    val unknown = strings.getString(R.string.diagnostics_field_unknown)
    return buildList {
        if (planned != null || written != null) {
            add(
                strings.getString(
                    R.string.diagnostics_tcp16_head_requests,
                    written?.toString() ?: unknown,
                    planned?.toString() ?: unknown,
                ),
            )
        }
        responses?.let { add(strings.getString(R.string.diagnostics_tcp16_head_responses, it)) }
        fields.count("bytesSent")?.let { add(strings.getString(R.string.diagnostics_tcp16_bytes_written, it)) }
        if (responses != null && responses > 0) {
            fields.count("bytesAtLastResponse")?.let {
                add(strings.getString(R.string.diagnostics_tcp16_last_response_bytes, it))
            }
        }
        if (covered != null) {
            val reached =
                strings.getString(
                    if (covered) R.string.shared_diagnostic_alive_yes else R.string.shared_diagnostic_alive_no,
                )
            add(strings.getString(R.string.diagnostics_tcp16_byte_coverage, reached))
        }
        if (!hasSeriesCounts || covered == null) {
            add(strings.getString(R.string.diagnostics_outcome_coverage_unknown))
        }
        fields["failureStage"]?.takeIf(String::isNotBlank)?.let { stage ->
            add(
                strings.getString(
                    R.string.diagnostics_matrix_failure_stage,
                    strings.getString(fatHeaderStageResource(stage)),
                ),
            )
        }
    }.toImmutableList()
}

private fun Map<String, String>.count(key: String): Long? = get(key)?.toLongOrNull()?.takeIf { it >= 0 }

private fun fatHeaderStageResource(stage: String): Int =
    when (stage) {
        "dns_resolution" -> R.string.diagnostics_stages_dns
        "tcp_connect" -> R.string.diagnostics_stages_tcp
        "socks5_negotiation" -> R.string.diagnostics_stages_proxy
        "tls_handshake" -> R.string.diagnostics_stages_tls
        "request_write" -> R.string.diagnostics_tcp16_stage_write
        "response_read", "http_response" -> R.string.diagnostics_stages_headers
        "coverage" -> R.string.diagnostics_matrix_inconclusive
        "administrative_stop" -> R.string.diagnostics_tcp16_stage_stopped
        else -> R.string.diagnostics_stages_unknown
    }
