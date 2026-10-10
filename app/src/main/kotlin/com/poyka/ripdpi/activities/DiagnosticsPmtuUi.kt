package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.PmtuAddressFamily
import com.poyka.ripdpi.diagnostics.PmtuPathScope
import com.poyka.ripdpi.diagnostics.PmtuProbeEvidence
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.parsePmtuProbeEvidence
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toImmutableList

internal fun ProbeResult.toPmtuGroup(
    strings: StringResolver,
    networkScopeUnverified: Boolean = false,
): DiagnosticsContextGroupUiModel? =
    if (probeType == "pmtu") {
        pmtuGroup(strings, parsePmtuProbeEvidence(details), networkScopeUnverified)
    } else {
        null
    }

internal fun pmtuGroup(
    strings: StringResolver,
    evidence: PmtuProbeEvidence?,
    networkScopeUnverified: Boolean = false,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title =
            strings.getString(
                when (evidence?.addressFamily) {
                    PmtuAddressFamily.IPV4 -> R.string.diagnostics_pmtu_ipv4
                    PmtuAddressFamily.IPV6 -> R.string.diagnostics_pmtu_ipv6
                    null -> R.string.diagnostics_pmtu_title
                },
            ),
        fields = PmtuFields(strings).fields(evidence, networkScopeUnverified).toImmutableList(),
        stackedFields = true,
    )

private class PmtuFields(
    private val strings: StringResolver,
) {
    private val unknown get() = strings.getString(R.string.diagnostics_ip_unknown)

    fun fields(
        evidence: PmtuProbeEvidence?,
        networkScopeUnverified: Boolean,
    ): List<DiagnosticsFieldUiModel> =
        buildList {
            if (evidence == null) {
                add(field(R.string.diagnostics_ip_status, strings.getString(R.string.diagnostics_ip_missing)))
            } else {
                addAll(observation(evidence))
                addAll(counters(evidence))
            }
            if (networkScopeUnverified || evidence?.reason == "network_scope_unverified") {
                add(field(R.string.diagnostics_ip_scope, strings.getString(R.string.diagnostics_ip_network_changed)))
            }
            add(field(R.string.diagnostics_ip_measurement, strings.getString(R.string.diagnostics_pmtu_caution)))
            add(field(R.string.diagnostics_ip_limits, strings.getString(R.string.diagnostics_pmtu_loss_caution)))
        }

    private fun observation(e: PmtuProbeEvidence): List<DiagnosticsFieldUiModel> =
        listOf(
            field(R.string.diagnostics_ip_status, strings.getString(e.status.pmtuLabelResource())),
            field(R.string.diagnostics_pmtu_lower_bound, bytes(e.acknowledgedUdpPayloadLowerBoundBytes)),
            field(R.string.diagnostics_pmtu_current, bytes(e.currentUdpPayloadBytes)),
            field(R.string.diagnostics_pmtu_ceiling, bytes(e.configuredUpperBoundUdpPayloadBytes)),
            field(R.string.diagnostics_pmtu_initial, bytes(e.initialUdpPayloadBytes)),
            field(R.string.diagnostics_pmtu_window, observed(e.observationWindowComplete)),
            field(R.string.diagnostics_pmtu_reached, observed(e.configuredUpperBoundReached)),
            field(R.string.diagnostics_http3_tls, observed(e.tlsValidated)),
            field(R.string.diagnostics_pmtu_fragmentation, observed(e.fragmentationPrevented)),
            field(R.string.diagnostics_ip_reason, strings.getString(pmtuReasonResource(e.reason))),
            field(
                R.string.diagnostics_ip_scope,
                strings.getString(
                    if (e.pathScope == PmtuPathScope.RAW_PATH) {
                        R.string.diagnostics_ip_raw
                    } else {
                        R.string.diagnostics_ip_proxy
                    },
                ),
            ),
        )

    private fun counters(e: PmtuProbeEvidence): List<DiagnosticsFieldUiModel> =
        listOf(
            field(R.string.diagnostics_pmtu_sent, number(e.sentProbeCount)),
            field(R.string.diagnostics_pmtu_lost, number(e.lostProbeCount)),
            field(R.string.diagnostics_pmtu_resets, number(e.blackHoleCount)),
            field(R.string.diagnostics_http3_elapsed, time(e.durationMs)),
            field(R.string.diagnostics_http3_handshake_time, time(e.handshakeElapsedMs)),
        )

    private fun observed(value: Boolean): String =
        strings.getString(if (value) R.string.diagnostics_http3_observed else R.string.diagnostics_http3_not_observed)

    private fun bytes(value: Int?): String =
        value?.let { strings.getString(R.string.diagnostics_pmtu_bytes, it) } ?: unknown

    private fun number(value: Long): String = strings.getString(R.string.diagnostics_http3_number, value)

    private fun time(value: Long?): String =
        value?.let { strings.getString(R.string.diagnostics_http3_milliseconds, it) } ?: unknown

    private fun field(
        label: Int,
        value: String,
    ) = DiagnosticsFieldUiModel(strings.getString(label), value)
}
