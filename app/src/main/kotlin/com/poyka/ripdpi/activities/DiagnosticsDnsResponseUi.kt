package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.DnsResponseSemantics
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.parseDnsResponseSemantics
import com.poyka.ripdpi.diagnostics.validatedDnsResponseSemantics
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toImmutableList

internal fun ProbeResult.toDnsResponseGroups(strings: StringResolver): List<DiagnosticsContextGroupUiModel> =
    if (probeType == "dns_integrity") {
        listOf(
            dnsResponseGroup(
                strings,
                R.string.diagnostics_dns_source_udp,
                parseDnsResponseSemantics(details, "udpDnsResponse"),
            ),
            dnsResponseGroup(
                strings,
                R.string.diagnostics_dns_source_encrypted,
                parseDnsResponseSemantics(details, "encryptedDnsResponse"),
            ),
        )
    } else {
        emptyList()
    }

internal fun dnsResponseGroup(
    strings: StringResolver,
    source: Int,
    response: DnsResponseSemantics?,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = strings.getString(source),
        fields = DnsResponseFields(strings).fields(response?.validatedDnsResponseSemantics()).toImmutableList(),
        stackedFields = true,
    )

private class DnsResponseFields(
    private val strings: StringResolver,
) {
    private val unknown get() = strings.getString(R.string.diagnostics_dns_unknown)

    fun fields(response: DnsResponseSemantics?): List<DiagnosticsFieldUiModel> =
        buildList {
            if (response == null) {
                add(field(R.string.diagnostics_dns_response, strings.getString(R.string.diagnostics_dns_missing)))
                add(field(R.string.diagnostics_dns_limits, strings.getString(R.string.diagnostics_dns_caution)))
                return@buildList
            }
            add(field(R.string.diagnostics_dns_query, response.queryType))
            add(field(R.string.diagnostics_dns_response, strings.getString(dnsOutcomeLabel(response.outcome))))
            add(field(R.string.diagnostics_dns_rcode, response.rcode?.toString() ?: unknown))
            add(
                field(
                    R.string.diagnostics_dns_cname,
                    response.cnameTargets.joinToString("\n").ifEmpty {
                        strings.getString(R.string.diagnostics_dns_none)
                    },
                ),
            )
            add(field(R.string.diagnostics_dns_ttl_min, response.ttlMinSeconds?.toString() ?: unknown))
            add(field(R.string.diagnostics_dns_ttl_max, response.ttlMaxSeconds?.toString() ?: unknown))
            add(field(R.string.diagnostics_dns_negative_ttl, response.negativeTtlSeconds?.toString() ?: unknown))
            add(field(R.string.diagnostics_dns_tc, flag(response.truncated)))
            add(field(R.string.diagnostics_dns_aa, flag(response.authoritative)))
            add(field(R.string.diagnostics_dns_ra, flag(response.recursionAvailable)))
            add(field(R.string.diagnostics_dns_ad, flag(response.authenticatedData)))
            add(field(R.string.diagnostics_dns_soa, flag(response.hasSoa)))
            add(
                field(
                    R.string.diagnostics_dns_ede,
                    response.extendedDnsErrorCodes.joinToString().ifEmpty {
                        strings.getString(R.string.diagnostics_dns_none)
                    },
                ),
            )
            add(field(R.string.diagnostics_dns_limits, strings.getString(R.string.diagnostics_dns_caution)))
        }

    private fun field(
        label: Int,
        value: String,
    ) = DiagnosticsFieldUiModel(strings.getString(label), value)

    private fun flag(value: Boolean?): String =
        when (value) {
            true -> strings.getString(R.string.diagnostics_dns_yes)
            false -> strings.getString(R.string.diagnostics_dns_no)
            null -> unknown
        }
}

internal fun dnsOutcomeLabel(outcome: DnsResponseOutcome): Int =
    when (outcome) {
        DnsResponseOutcome.ANSWER -> R.string.diagnostics_dns_answer
        DnsResponseOutcome.NODATA -> R.string.diagnostics_dns_nodata
        DnsResponseOutcome.NXDOMAIN -> R.string.diagnostics_dns_nxdomain
        DnsResponseOutcome.SERVFAIL -> R.string.diagnostics_dns_servfail
        DnsResponseOutcome.REFUSED -> R.string.diagnostics_dns_refused
        DnsResponseOutcome.OTHER_RCODE -> R.string.diagnostics_dns_other_rcode
        DnsResponseOutcome.TRUNCATED -> R.string.diagnostics_dns_truncated
        DnsResponseOutcome.TIMEOUT -> R.string.diagnostics_dns_timeout
        DnsResponseOutcome.TRANSPORT_ERROR -> R.string.diagnostics_dns_transport_error
        DnsResponseOutcome.MALFORMED -> R.string.diagnostics_dns_malformed
        DnsResponseOutcome.NOT_OBSERVED -> R.string.diagnostics_dns_missing
    }
