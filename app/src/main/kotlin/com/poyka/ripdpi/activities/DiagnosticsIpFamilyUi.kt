package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.IpFamilyProbeEvidence
import com.poyka.ripdpi.diagnostics.IpProbeFamily
import com.poyka.ripdpi.diagnostics.IpProbePathScope
import com.poyka.ripdpi.diagnostics.IpProbeStage
import com.poyka.ripdpi.diagnostics.IpProbeStatus
import com.poyka.ripdpi.diagnostics.Nat64DiscoveryStatus
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.parseIpFamilyProbeEvidence
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toImmutableList

internal fun ProbeResult.toIpFamilyGroup(
    strings: StringResolver,
    networkScopeUnverified: Boolean = false,
): DiagnosticsContextGroupUiModel? =
    if (probeType ==
        "ip_family"
    ) {
        ipFamilyGroup(strings, parseIpFamilyProbeEvidence(details), networkScopeUnverified)
    } else {
        null
    }

internal fun ipFamilyGroup(
    strings: StringResolver,
    evidence: IpFamilyProbeEvidence?,
    networkScopeUnverified: Boolean = false,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title =
            evidence?.family?.let { strings.getString(it.labelResource()) }
                ?: strings.getString(R.string.diagnostics_ip_title),
        fields = IpFamilyFields(strings).fields(evidence, networkScopeUnverified).toImmutableList(),
        stackedFields = true,
    )

private class IpFamilyFields(
    private val strings: StringResolver,
) {
    private val unknown get() = strings.getString(R.string.diagnostics_ip_unknown)

    fun fields(
        evidence: IpFamilyProbeEvidence?,
        networkScopeUnverified: Boolean,
    ): List<DiagnosticsFieldUiModel> =
        buildList {
            if (evidence == null) {
                add(field(R.string.diagnostics_ip_status, strings.getString(R.string.diagnostics_ip_missing)))
            } else {
                add(field(R.string.diagnostics_ip_status, strings.getString(evidence.status.labelResource())))
                add(
                    field(
                        R.string.diagnostics_ip_stage,
                        strings.getString(
                            if (evidence.stage == IpProbeStage.TCP_CONNECT) {
                                R.string.diagnostics_ip_tcp
                            } else {
                                R.string.diagnostics_ip_dns64
                            },
                        ),
                    ),
                )
                add(field(R.string.diagnostics_ip_reason, strings.getString(ipReasonResource(evidence.reason))))
                add(field(R.string.diagnostics_ip_duration, evidence.durationMs?.toString() ?: unknown))
                add(field(R.string.diagnostics_ip_attempts, evidence.attemptCount.toString()))
                add(
                    field(
                        R.string.diagnostics_ip_scope,
                        strings.getString(
                            if (evidence.pathScope == IpProbePathScope.RAW_PATH) {
                                R.string.diagnostics_ip_raw
                            } else {
                                R.string.diagnostics_ip_proxy
                            },
                        ),
                    ),
                )
                if (evidence.family == IpProbeFamily.NAT64) addAll(nat64Fields(evidence))
                if (evidence.family == IpProbeFamily.IPV4) {
                    add(field(R.string.diagnostics_ip_limits, strings.getString(R.string.diagnostics_ip_clat_caution)))
                }
            }
            if (networkScopeUnverified) {
                add(field(R.string.diagnostics_ip_scope, strings.getString(R.string.diagnostics_ip_network_changed)))
            }
            add(field(R.string.diagnostics_ip_measurement, strings.getString(R.string.diagnostics_ip_caution)))
        }

    private fun nat64Fields(evidence: IpFamilyProbeEvidence): List<DiagnosticsFieldUiModel> =
        buildList {
            add(field(R.string.diagnostics_ip_discovery, strings.getString(evidence.discoveryStatus.labelResource())))
            add(field(R.string.diagnostics_ip_prefix_length, evidence.prefixLength?.toString() ?: unknown))
            add(
                field(
                    R.string.diagnostics_ip_resolver,
                    strings.getString(
                        if (evidence.resolverSource == "NETWORK_SNAPSHOT") {
                            R.string.diagnostics_ip_network_dns
                        } else {
                            R.string.diagnostics_ip_unknown
                        },
                    ),
                ),
            )
            add(field(R.string.diagnostics_ip_limits, strings.getString(R.string.diagnostics_ip_nat64_caution)))
        }

    private fun field(
        label: Int,
        value: String,
    ) = DiagnosticsFieldUiModel(strings.getString(label), value)
}

internal fun IpProbeStatus.labelResource(): Int =
    when (this) {
        IpProbeStatus.REACHABLE -> R.string.diagnostics_ip_reachable
        IpProbeStatus.FAILED -> R.string.diagnostics_ip_failed
        IpProbeStatus.TIMEOUT -> R.string.diagnostics_ip_timeout
        IpProbeStatus.NOT_OBSERVED -> R.string.diagnostics_ip_missing
        IpProbeStatus.UNSUPPORTED -> R.string.diagnostics_ip_unsupported
        IpProbeStatus.CANCELLED -> R.string.diagnostics_ip_cancelled
        IpProbeStatus.INVALID -> R.string.diagnostics_ip_invalid
    }

private fun IpProbeFamily.labelResource(): Int =
    when (this) {
        IpProbeFamily.IPV4 -> R.string.diagnostics_ip_v4
        IpProbeFamily.IPV6 -> R.string.diagnostics_ip_v6
        IpProbeFamily.NAT64 -> R.string.diagnostics_ip_nat64
    }

private fun Nat64DiscoveryStatus?.labelResource(): Int =
    when (this) {
        Nat64DiscoveryStatus.DISCOVERED -> R.string.diagnostics_ip_discovered
        Nat64DiscoveryStatus.NO_PREFIX -> R.string.diagnostics_ip_no_prefix
        Nat64DiscoveryStatus.FAILED -> R.string.diagnostics_ip_failed
        Nat64DiscoveryStatus.INVALID -> R.string.diagnostics_ip_invalid
        Nat64DiscoveryStatus.TIMEOUT -> R.string.diagnostics_ip_timeout
        Nat64DiscoveryStatus.NOT_RUN, null -> R.string.diagnostics_ip_missing
    }

private fun ipReasonResource(reason: String?): Int =
    when (reason) {
        "timeout", "dns_timeout", "deadline_exceeded" -> R.string.diagnostics_ip_timeout
        "connection_refused" -> R.string.diagnostics_ip_refused
        "network_unreachable", "host_unreachable" -> R.string.diagnostics_ip_unreachable
        "permission_denied" -> R.string.diagnostics_ip_permission
        "io_error" -> R.string.diagnostics_ip_io
        "no_network_dns" -> R.string.diagnostics_ip_no_dns
        "dns_error" -> R.string.diagnostics_ip_dns_error
        "dns_no_data", "no_prefix" -> R.string.diagnostics_ip_no_prefix
        "invalid_dns64_response", "ambiguous_prefix" -> R.string.diagnostics_ip_invalid_prefix
        "unsupported_path" -> R.string.diagnostics_ip_proxy
        "invalid_config" -> R.string.diagnostics_ip_invalid_config
        "cancelled" -> R.string.diagnostics_ip_cancelled
        "network_scope_unverified" -> R.string.diagnostics_ip_network_changed
        else -> R.string.diagnostics_ip_unknown
    }
