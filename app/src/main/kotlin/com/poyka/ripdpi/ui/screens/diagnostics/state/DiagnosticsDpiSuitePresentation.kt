package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityVerdict
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeResult
import com.poyka.ripdpi.diagnostics.dpi.EchProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.HttpsRrLookupAttempt
import com.poyka.ripdpi.diagnostics.dpi.HttpsRrOutcome
import com.poyka.ripdpi.diagnostics.dpi.HttpsRrSource
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict
import com.poyka.ripdpi.diagnostics.dpi.SuiteVerdict
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Verdict
import com.poyka.ripdpi.diagnostics.dpi.TelegramTestVerdict
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.userMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import java.util.Locale

internal fun DpiSuiteProbeResult.toDpiSuiteProbeRowUiModel(
    stringResolver: StringResolver,
): DiagnosticsDpiSuiteProbeRowUiModel =
    DiagnosticsDpiSuiteProbeRowUiModel(
        kind = kind,
        label = kind.displayLabel(stringResolver),
        status = statusLabel(stringResolver),
        detail = detailLabel(stringResolver),
        tone = tone(),
        detailRows = detailRows(stringResolver),
    )

private fun DpiSuiteProbeResult.detailRows(
    stringResolver: StringResolver,
): ImmutableList<DiagnosticsDpiSuiteProbeDetailUiModel> =
    when (this) {
        is DpiSuiteProbeResult.DomainReachability -> {
            results.mapNotNull { result -> result.tlsClientState }.toDiagnosticsTlsDetailRows(stringResolver)
        }

        is DpiSuiteProbeResult.Tcp16 -> {
            results.mapNotNull { result -> result.tlsClientState }.toDiagnosticsTlsDetailRows(stringResolver)
        }

        is DpiSuiteProbeResult.QuicH3 -> {
            results.map { result -> result.toQuicDetailRow(stringResolver) }.toPersistentList()
        }

        is DpiSuiteProbeResult.EchReadiness -> {
            results.map { result -> result.toEchDetailRow(stringResolver) }.toPersistentList()
        }

        else -> {
            persistentListOf()
        }
    }

private fun List<DiagnosticsTlsClientState>.toDiagnosticsTlsDetailRows(
    stringResolver: StringResolver,
): ImmutableList<DiagnosticsDpiSuiteProbeDetailUiModel> {
    val state = firstOrNull() ?: return persistentListOf()
    val mode =
        stringResolver.getString(
            when {
                state.nativeOwnedTlsAvailable -> R.string.diagnostics_tls_mode_native_owned
                state.fallbackActive -> R.string.diagnostics_tls_mode_android_fallback
                else -> R.string.diagnostics_tls_mode_default
            },
        )
    val tone =
        when {
            state.fallbackActive -> DiagnosticsTone.Warning
            state.nativeOwnedTlsAvailable -> DiagnosticsTone.Positive
            else -> DiagnosticsTone.Neutral
        }
    return buildList {
        state.profileId?.let { profile ->
            add(
                DiagnosticsDpiSuiteProbeDetailUiModel(
                    label = stringResolver.getString(R.string.diagnostics_tls_metric_profile),
                    detail = profile,
                    tone = DiagnosticsTone.Info,
                ),
            )
        }
        add(
            DiagnosticsDpiSuiteProbeDetailUiModel(
                label = stringResolver.getString(R.string.diagnostics_tls_metric_mode),
                detail = mode,
                tone = tone,
            ),
        )
    }.toPersistentList()
}

private fun QuicProbeResult.toQuicDetailRow(stringResolver: StringResolver): DiagnosticsDpiSuiteProbeDetailUiModel =
    DiagnosticsDpiSuiteProbeDetailUiModel(
        label = target,
        detail =
            listOf(
                stringResolver.getString(
                    R.string.diagnostics_dpi_suite_chrome_format,
                    chromeOk.okBlockedLabel(stringResolver),
                ),
                stringResolver.getString(
                    R.string.diagnostics_dpi_suite_firefox_format,
                    firefoxOk.okBlockedLabel(stringResolver),
                ),
                stringResolver.getString(
                    R.string.diagnostics_dpi_suite_generic_format,
                    genericOk.okBlockedLabel(stringResolver),
                ),
                stringResolver.getString(R.string.diagnostics_dpi_suite_vn_format, vnOk.okBlockedLabel(stringResolver)),
                latencyLabel(stringResolver),
            ).joinToString(" | "),
        tone =
            countTone(
                if (verdict == QuicProbeVerdict.QUIC_OK) {
                    0
                } else {
                    1
                },
            ),
    )

private fun Boolean.okBlockedLabel(stringResolver: StringResolver): String =
    if (this) {
        stringResolver.getString(
            R.string.diagnostics_cidr_whitelist_status_ok,
        )
    } else {
        stringResolver.getString(R.string.diagnostics_dpi_suite_blocked)
    }

private fun QuicProbeResult.latencyLabel(stringResolver: StringResolver): String =
    serverInitialLatencyMs
        ?.let { latency -> stringResolver.getString(R.string.diagnostics_transfer_ms, latency) }
        ?: stringResolver.getString(R.string.diagnostics_dpi_suite_no_latency)

private fun EchProbeResult.toEchDetailRow(stringResolver: StringResolver): DiagnosticsDpiSuiteProbeDetailUiModel =
    DiagnosticsDpiSuiteProbeDetailUiModel(
        label = target,
        detail =
            buildList {
                add(verdictLabel(stringResolver))
                add(
                    stringResolver.getString(
                        R.string.diagnostics_dpi_suite_https_rr_format,
                        httpsRrFetched.yesNoLabel(stringResolver),
                    ),
                )
                addAll(httpsLookups.distinct().map { it.label(stringResolver) })
                if (hasMeasurement) {
                    add(
                        stringResolver.getString(
                            R.string.diagnostics_dpi_suite_negotiated_format,
                            negotiatedEch.yesNoLabel(stringResolver),
                        ),
                    )
                }
                add(vanillaTlsLabel(stringResolver))
                add(latencyLabel(stringResolver))
            }.joinToString(" | "),
        tone = echTone(),
    )

private fun EchProbeResult.verdictLabel(stringResolver: StringResolver): String =
    when (verdict) {
        EchProbeVerdict.ECH_OK -> stringResolver.getString(R.string.diagnostics_dpi_suite_ech_ok)
        EchProbeVerdict.ECH_REJECTED -> stringResolver.getString(R.string.diagnostics_dpi_suite_ech_rejected)
        EchProbeVerdict.ECH_NO_CONFIG -> stringResolver.getString(R.string.diagnostics_dpi_suite_no_ech_config)
        EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE -> stringResolver.getString(R.string.diagnostics_pmtu_inconclusive)
        EchProbeVerdict.ECH_UNKNOWN_KEY -> stringResolver.getString(R.string.diagnostics_dpi_suite_unknown_ech_key)
        EchProbeVerdict.ECH_NETWORK_BLOCK -> stringResolver.getString(R.string.diagnostics_dpi_suite_network_blocked)
    }

private fun HttpsRrLookupAttempt.label(stringResolver: StringResolver): String {
    val sourceLabel =
        when (source) {
            HttpsRrSource.DOH -> R.string.diagnostics_dns_source_doh_wire
            HttpsRrSource.SYSTEM -> R.string.diagnostics_stages_lane_dns_system
        }
    val outcomeLabel =
        when (outcome) {
            HttpsRrOutcome.ANSWER -> R.string.diagnostics_cidr_whitelist_status_ok

            HttpsRrOutcome.NODATA -> R.string.diagnostics_dns_nodata

            HttpsRrOutcome.NXDOMAIN -> R.string.diagnostics_dns_nxdomain

            HttpsRrOutcome.DNS_ERROR -> R.string.diagnostics_dns_other_rcode

            HttpsRrOutcome.TRUNCATED -> R.string.diagnostics_dns_truncated

            HttpsRrOutcome.MALFORMED -> R.string.diagnostics_dns_malformed

            HttpsRrOutcome.TIMEOUT -> R.string.diagnostics_dns_timeout

            HttpsRrOutcome.TRANSPORT_ERROR, HttpsRrOutcome.HTTP_ERROR -> R.string.diagnostics_dns_transport_error

            HttpsRrOutcome.PLATFORM_UNSUPPORTED -> R.string.diagnostics_ip_unsupported

            HttpsRrOutcome.NOT_OBSERVED,
            HttpsRrOutcome.ALIAS_LIMIT,
            HttpsRrOutcome.SERVICE_UNAVAILABLE,
            -> R.string.diagnostics_dns_missing
        }
    val detail =
        when (outcome) {
            HttpsRrOutcome.HTTP_ERROR -> " (HTTP $httpStatus)"
            HttpsRrOutcome.DNS_ERROR -> " (RCODE $rcode)"
            else -> ""
        }
    return "${stringResolver.getString(sourceLabel)}: ${stringResolver.getString(outcomeLabel)}$detail"
}

private fun Boolean.yesNoLabel(stringResolver: StringResolver): String =
    if (this) {
        stringResolver.getString(
            R.string.diagnostics_dpi_suite_yes,
        )
    } else {
        stringResolver.getString(R.string.diagnostics_dpi_suite_no)
    }

private fun EchProbeResult.vanillaTlsLabel(stringResolver: StringResolver): String =
    when (vanillaTlsOk) {
        true -> stringResolver.getString(R.string.diagnostics_dpi_suite_vanilla_ok)
        false -> stringResolver.getString(R.string.diagnostics_dpi_suite_vanilla_blocked)
        null -> stringResolver.getString(R.string.diagnostics_dpi_suite_vanilla_unknown)
    }

private fun EchProbeResult.latencyLabel(stringResolver: StringResolver): String =
    tlsLatencyMs
        ?.let { latency -> stringResolver.getString(R.string.diagnostics_transfer_ms, latency) }
        ?: stringResolver.getString(R.string.diagnostics_dpi_suite_no_latency)

private fun EchProbeResult.echTone(): DiagnosticsTone =
    when (verdict) {
        EchProbeVerdict.ECH_OK -> DiagnosticsTone.Positive

        EchProbeVerdict.ECH_NO_CONFIG,
        EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE,
        -> DiagnosticsTone.Neutral

        EchProbeVerdict.ECH_REJECTED,
        EchProbeVerdict.ECH_UNKNOWN_KEY,
        EchProbeVerdict.ECH_NETWORK_BLOCK,
        -> DiagnosticsTone.Warning
    }

private fun DpiSuiteProbeResult.statusLabel(stringResolver: StringResolver): String {
    val resource =
        when (this) {
            is DpiSuiteProbeResult.DnsIntegrity -> {
                flaggedStatusResource(
                    result.domains.any { domain -> domain.verdict != DnsIntegrityVerdict.DNS_OK },
                )
            }

            is DpiSuiteProbeResult.DnsAvailability -> {
                if (results.all { server -> server.availableDomains > 0 }) {
                    R.string.diagnostics_cidr_whitelist_status_ok
                } else {
                    R.string.diagnostics_dpi_suite_partial
                }
            }

            is DpiSuiteProbeResult.DomainReachability -> {
                flaggedStatusResource(
                    results.any { domain -> domain.verdict != DomainVerdict.OK },
                )
            }

            is DpiSuiteProbeResult.Tcp16 -> {
                if (results.none { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }) {
                    R.string.diagnostics_cidr_whitelist_status_ok
                } else {
                    R.string.diagnostics_tcp16_metric_detected
                }
            }

            is DpiSuiteProbeResult.AllowlistSni -> {
                if (results.isEmpty()) {
                    R.string.asset_provider_version_none
                } else {
                    R.string.diagnostics_byoh_metric_compatible
                }
            }

            is DpiSuiteProbeResult.Telegram -> {
                return result.verdict.name.dpiStatusLabel(stringResolver)
            }

            is DpiSuiteProbeResult.QuicH3 -> {
                flaggedStatusResource(
                    results.any { result -> result.verdict != QuicProbeVerdict.QUIC_OK },
                )
            }

            is DpiSuiteProbeResult.EchReadiness -> {
                when {
                    results.none { it.hasMeasurement } -> {
                        R.string.diagnostics_pmtu_inconclusive
                    }

                    results.any { it.hasMeasurement && it.verdict != EchProbeVerdict.ECH_OK } -> {
                        R.string.diagnostics_dns_integrity_metric_flagged
                    }

                    results.any { !it.hasMeasurement } -> {
                        R.string.diagnostics_dpi_suite_partial
                    }

                    else -> {
                        R.string.diagnostics_cidr_whitelist_status_ok
                    }
                }
            }

            is DpiSuiteProbeResult.Skipped -> {
                R.string.diagnostics_dpi_suite_skipped
            }

            is DpiSuiteProbeResult.Failed -> {
                R.string.status_connection_stage_state_failed
            }
        }
    return stringResolver.getString(resource)
}

private fun flaggedStatusResource(flagged: Boolean): Int =
    if (flagged) R.string.diagnostics_dns_integrity_metric_flagged else R.string.diagnostics_cidr_whitelist_status_ok

private fun DpiSuiteProbeResult.detailLabel(stringResolver: StringResolver): String =
    when (this) {
        is DpiSuiteProbeResult.DnsIntegrity -> {
            val flagged = result.domains.count { domain -> domain.verdict != DnsIntegrityVerdict.DNS_OK }
            stringResolver.getString(
                R.string.diagnostics_dpi_suite_dns_integrity_format,
                flagged,
                result.domains.size,
                result.stubIps.size,
            )
        }

        is DpiSuiteProbeResult.DnsAvailability -> {
            val available = results.count { server -> server.availableDomains > 0 }
            stringResolver.getString(R.string.diagnostics_dpi_suite_dns_availability_format, available, results.size)
        }

        is DpiSuiteProbeResult.DomainReachability -> {
            val ok = results.count { domain -> domain.verdict == DomainVerdict.OK }
            stringResolver.getString(R.string.diagnostics_dpi_suite_domains_format, ok, results.size)
        }

        is DpiSuiteProbeResult.Tcp16 -> {
            val detected = results.count { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
            stringResolver.getString(R.string.diagnostics_dpi_suite_tcp16_format, detected, results.size)
        }

        is DpiSuiteProbeResult.AllowlistSni -> {
            stringResolver.getString(R.string.diagnostics_dpi_suite_asn_format, results.size)
        }

        is DpiSuiteProbeResult.Telegram -> {
            val reachable = result.dcResults.count { dc -> dc.reachable }
            stringResolver.getString(
                R.string.diagnostics_dpi_suite_telegram_format,
                result.download.status.name
                    .dpiStatusLabel(stringResolver),
                result.upload.status.name
                    .dpiStatusLabel(stringResolver),
                reachable,
                result.dcResults.size,
            )
        }

        is DpiSuiteProbeResult.QuicH3 -> {
            val ok = results.count { result -> result.verdict == QuicProbeVerdict.QUIC_OK }
            stringResolver.getString(R.string.diagnostics_dpi_suite_quic_format, ok, results.size)
        }

        is DpiSuiteProbeResult.EchReadiness -> {
            val ok = results.count { result -> result.verdict == EchProbeVerdict.ECH_OK }
            stringResolver.getString(R.string.diagnostics_dpi_suite_ech_format, ok, results.size)
        }

        is DpiSuiteProbeResult.Skipped -> {
            if (reason == "TCP16 detected zero blocked ASNs") {
                stringResolver.getString(R.string.diagnostics_dpi_suite_tcp16_no_blocked_asns)
            } else {
                reason
            }
        }

        is DpiSuiteProbeResult.Failed -> {
            error
        }
    }

private fun DpiSuiteProbeResult.tone(): DiagnosticsTone =
    when (this) {
        is DpiSuiteProbeResult.Failed -> {
            DiagnosticsTone.Negative
        }

        is DpiSuiteProbeResult.Skipped -> {
            DiagnosticsTone.Neutral
        }

        is DpiSuiteProbeResult.DnsIntegrity -> {
            countTone(
                result.domains.count { domain -> domain.verdict != DnsIntegrityVerdict.DNS_OK },
            )
        }

        is DpiSuiteProbeResult.DnsAvailability -> {
            countTone(
                results.count { server -> server.availableDomains == 0 },
            )
        }

        is DpiSuiteProbeResult.DomainReachability -> {
            countTone(
                results.count { domain -> domain.verdict != DomainVerdict.OK },
            )
        }

        is DpiSuiteProbeResult.Tcp16 -> {
            countTone(
                results.count { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB },
            )
        }

        is DpiSuiteProbeResult.AllowlistSni -> {
            DiagnosticsTone.Neutral
        }

        is DpiSuiteProbeResult.Telegram -> {
            when (result.verdict) {
                TelegramTestVerdict.OK -> {
                    DiagnosticsTone.Positive
                }

                TelegramTestVerdict.PARTIAL,
                TelegramTestVerdict.SLOW,
                -> {
                    DiagnosticsTone.Warning
                }

                TelegramTestVerdict.BLOCKED,
                TelegramTestVerdict.ERROR,
                -> {
                    DiagnosticsTone.Negative
                }
            }
        }

        is DpiSuiteProbeResult.QuicH3 -> {
            val flagged = results.count { result -> result.verdict != QuicProbeVerdict.QUIC_OK }
            countTone(flagged)
        }

        is DpiSuiteProbeResult.EchReadiness -> {
            if (results.none { it.hasMeasurement }) {
                DiagnosticsTone.Neutral
            } else {
                countTone(results.count { it.verdict != EchProbeVerdict.ECH_OK })
            }
        }
    }

internal fun DpiProbeKind.displayLabel(stringResolver: StringResolver): String =
    when (this) {
        DpiProbeKind.DNS_INTEGRITY -> {
            stringResolver.getString(R.string.diagnostics_tool_dns_integrity_title)
        }

        DpiProbeKind.DNS_AVAILABILITY -> {
            stringResolver.getString(R.string.diagnostics_tool_dns_availability_title)
        }

        DpiProbeKind.DOMAIN_REACHABILITY -> {
            stringResolver.getString(
                R.string.diagnostics_tool_domain_reachability_title,
            )
        }

        DpiProbeKind.TCP16 -> {
            stringResolver.getString(R.string.diagnostics_dpi_probe_kind_tcp16)
        }

        DpiProbeKind.WHITELIST_SNI -> {
            stringResolver.getString(R.string.diagnostics_tool_sni_compatibility_title)
        }

        DpiProbeKind.TELEGRAM -> {
            stringResolver.getString(R.string.connection_health_destination_telegram)
        }

        DpiProbeKind.QUIC_H3 -> {
            stringResolver.getString(R.string.diagnostics_dpi_probe_kind_quic_h3)
        }

        DpiProbeKind.ECH_READINESS -> {
            stringResolver.getString(R.string.diagnostics_dpi_probe_kind_ech)
        }
    }

internal fun SuiteVerdict.summary(stringResolver: StringResolver): String =
    when (this) {
        SuiteVerdict.CLEAN -> {
            stringResolver.getString(
                R.string.diagnostics_dpi_suite_no_selected_dpi_probe_reported_warnings,
            )
        }

        SuiteVerdict.DPI_DETECTED -> {
            stringResolver.getString(
                R.string.diagnostics_dpi_suite_one_or_more_selected_probes_reported_dpi_like_interference,
            )
        }

        SuiteVerdict.DNS_INTERFERENCE -> {
            stringResolver.getString(
                R.string.diagnostics_dpi_suite_dns_integrity_probes_reported_resolver_path_interference,
            )
        }

        SuiteVerdict.THROTTLING -> {
            stringResolver.getString(
                R.string.diagnostics_dpi_suite_telegram_transfer_probes_reported_throughput_degradation,
            )
        }

        SuiteVerdict.MIXED -> {
            stringResolver.getString(
                R.string.diagnostics_dpi_suite_multiple_interference_categories_were_reported,
            )
        }

        SuiteVerdict.INCONCLUSIVE -> {
            stringResolver.getString(
                R.string.diagnostics_dpi_suite_too_many_selected_probes_failed_to_classify_the_path,
            )
        }
    }

private fun countTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }

internal fun SuiteVerdict.label(stringResolver: StringResolver): String =
    stringResolver.getString(
        when (this) {
            SuiteVerdict.CLEAN -> R.string.diagnostics_dpi_suite_verdict_clean
            SuiteVerdict.DPI_DETECTED -> R.string.diagnostics_dpi_suite_verdict_dpi_detected
            SuiteVerdict.DNS_INTERFERENCE -> R.string.diagnostics_dpi_suite_verdict_dns_interference
            SuiteVerdict.THROTTLING -> R.string.diagnostics_family_throttling
            SuiteVerdict.MIXED -> R.string.diagnostics_dpi_suite_verdict_mixed
            SuiteVerdict.INCONCLUSIVE -> R.string.diagnostics_pmtu_inconclusive
        },
    )

private fun String.dpiStatusLabel(stringResolver: StringResolver): String =
    when (lowercase(Locale.US)) {
        "ok" -> stringResolver.getString(R.string.diagnostics_cidr_whitelist_status_ok)
        "partial" -> stringResolver.getString(R.string.diagnostics_dpi_suite_partial)
        "slow" -> stringResolver.getString(R.string.diagnostics_dpi_suite_slow)
        "stalled" -> stringResolver.getString(R.string.diagnostics_dpi_suite_stalled)
        "blocked" -> stringResolver.getString(R.string.diagnostics_dpi_suite_blocked)
        "error" -> stringResolver.getString(R.string.diagnostics_dpi_suite_error)
        else -> this
    }

internal fun DiagnosticsDpiSuiteToolUiModel.withFailure(
    error: Throwable,
    stringResolver: StringResolver,
): DiagnosticsDpiSuiteToolUiModel =
    if (error is CancellationException) {
        copy(
            state = DiagnosticsDpiSuiteState.Cancelled,
            summary = stringResolver.getString(R.string.diagnostics_dpi_suite_cancelled),
        )
    } else {
        copy(
            state = DiagnosticsDpiSuiteState.Failed,
            summary = stringResolver.getString(R.string.diagnostics_dpi_suite_failed_summary),
            errorMessage = stringResolver.userMessage(error),
        )
    }
