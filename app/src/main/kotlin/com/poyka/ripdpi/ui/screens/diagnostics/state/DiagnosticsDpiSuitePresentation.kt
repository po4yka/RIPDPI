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
import com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeSample
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeStartFailure
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
        label = peerAddress?.takeUnless { it == target }?.let { "$target · $it" } ?: target,
        detail =
            startFailure?.label(stringResolver)
                ?: QuicFingerprint.entries.joinToString(" | ") { fingerprint ->
                    val samples = attempts[fingerprint].orEmpty()
                    val details =
                        samples.takeIf { it.isNotEmpty() }?.joinToString(" → ") { it.label(stringResolver) }
                            ?: stringResolver.getString(R.string.diagnostics_matrix_stage_not_run)
                    stringResolver.getString(fingerprint.detailFormat, details)
                },
        tone = verdict.tone(),
    )

private val QuicFingerprint.detailFormat: Int
    get() =
        when (this) {
            QuicFingerprint.CHROME_120 -> R.string.diagnostics_dpi_suite_quic_template_a_format
            QuicFingerprint.FIREFOX_121 -> R.string.diagnostics_dpi_suite_quic_template_b_format
            QuicFingerprint.GENERIC_V1 -> R.string.diagnostics_dpi_suite_generic_format
            QuicFingerprint.VN_PROBE -> R.string.diagnostics_dpi_suite_vn_format
        }

private fun QuicProbeStartFailure.label(stringResolver: StringResolver): String =
    stringResolver.getString(
        when (this) {
            QuicProbeStartFailure.DNS_TIMEOUT -> R.string.diagnostics_error_timeout
            QuicProbeStartFailure.DNS_ERROR -> R.string.diagnostics_error_dns_failure
        },
    )

private fun QuicProbeSample.label(stringResolver: StringResolver): String {
    val label =
        when (outcome) {
            QuicProbeOutcome.INITIAL_CRYPTO -> {
                "Initial + CRYPTO"
            }

            QuicProbeOutcome.INITIAL_ACK -> {
                "Initial + ACK"
            }

            QuicProbeOutcome.INITIAL_OTHER -> {
                "Initial"
            }

            QuicProbeOutcome.INITIAL_CLOSE -> {
                "CONNECTION_CLOSE"
            }

            QuicProbeOutcome.RETRY -> {
                "Retry"
            }

            QuicProbeOutcome.VERSION_NEGOTIATION -> {
                stringResolver.getString(R.string.diagnostics_stages_observed)
            }

            QuicProbeOutcome.NO_RESPONSE -> {
                stringResolver.getString(R.string.diagnostics_stages_timed_out)
            }

            QuicProbeOutcome.UNCORRELATED_RESPONSE -> {
                stringResolver.getString(R.string.diagnostics_dpi_suite_quic_unmatched)
            }

            QuicProbeOutcome.RECEIVE_LIMIT_REACHED -> {
                stringResolver.getString(R.string.diagnostics_dpi_suite_quic_receive_limit)
            }

            QuicProbeOutcome.TRANSPORT_ERROR -> {
                stringResolver.getString(R.string.diagnostics_error_connect_failure)
            }

            QuicProbeOutcome.VALIDATION_ERROR -> {
                stringResolver.getString(R.string.diagnostics_dpi_suite_quic_validation_error)
            }

            QuicProbeOutcome.PACKET_BUILD_ERROR -> {
                stringResolver.getString(R.string.diagnostics_error_start_failed)
            }

            QuicProbeOutcome.BUDGET_EXHAUSTED -> {
                stringResolver.getString(R.string.diagnostics_matrix_stage_not_run)
            }
        }
    return latencyMs?.let { "$label (${stringResolver.getString(R.string.diagnostics_transfer_ms, it)})" } ?: label
}

private fun QuicProbeVerdict.tone(): DiagnosticsTone =
    when (this) {
        QuicProbeVerdict.QUIC_OK -> DiagnosticsTone.Positive
        QuicProbeVerdict.QUIC_DEGRADED -> DiagnosticsTone.Warning
        QuicProbeVerdict.QUIC_INCONCLUSIVE -> DiagnosticsTone.Neutral
    }

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
        EchProbeVerdict.ECH_OK -> {
            stringResolver.getString(R.string.diagnostics_dpi_suite_ech_ok)
        }

        EchProbeVerdict.ECH_REJECTED -> {
            stringResolver.getString(R.string.diagnostics_dpi_suite_ech_rejected)
        }

        EchProbeVerdict.ECH_NO_CONFIG -> {
            stringResolver.getString(R.string.diagnostics_dpi_suite_no_ech_config)
        }

        EchProbeVerdict.ECH_LOOKUP_INCONCLUSIVE -> {
            stringResolver.getString(R.string.diagnostics_pmtu_inconclusive)
        }

        EchProbeVerdict.ECH_UNSUPPORTED_ENDPOINT -> {
            stringResolver.getString(R.string.diagnostics_dpi_suite_ech_endpoint_unsupported)
        }

        EchProbeVerdict.ECH_UNUSABLE_CONFIG -> {
            stringResolver.getString(R.string.diagnostics_dpi_suite_ech_config_unusable)
        }

        EchProbeVerdict.ECH_UNKNOWN_KEY -> {
            stringResolver.getString(R.string.diagnostics_dpi_suite_unknown_ech_key)
        }

        EchProbeVerdict.ECH_NETWORK_BLOCK -> {
            stringResolver.getString(R.string.diagnostics_dpi_suite_network_blocked)
        }
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
        EchProbeVerdict.ECH_UNSUPPORTED_ENDPOINT,
        EchProbeVerdict.ECH_UNUSABLE_CONFIG,
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
                statusResource()
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
                when (quicTone()) {
                    DiagnosticsTone.Positive -> R.string.diagnostics_cidr_whitelist_status_ok
                    DiagnosticsTone.Warning -> R.string.diagnostics_dpi_suite_partial
                    else -> R.string.diagnostics_dpi_suite_quic_inconclusive
                }
            }

            is DpiSuiteProbeResult.EchReadiness -> {
                statusResource()
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

private fun DpiSuiteProbeResult.DnsIntegrity.statusResource(): Int =
    when {
        result.domains.isNotEmpty() && result.domains.all { it.verdict == DnsIntegrityVerdict.DNS_OK } -> {
            R.string.diagnostics_cidr_whitelist_status_ok
        }

        result.domains.any {
            it.verdict == DnsIntegrityVerdict.DNS_DISAGREEMENT ||
                it.verdict == DnsIntegrityVerdict.DNS_FAILURE_AGREEMENT ||
                it.verdict == DnsIntegrityVerdict.FAKE_IP
        } -> {
            R.string.diagnostics_dns_integrity_metric_flagged
        }

        else -> {
            R.string.diagnostics_dpi_suite_partial
        }
    }

private fun DpiSuiteProbeResult.EchReadiness.statusResource(): Int =
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
            val summary =
                stringResolver.getString(
                    R.string.diagnostics_dpi_suite_quic_format,
                    ok,
                    results.size + skippedTargetCount,
                )
            if (skippedTargetCount > 0) {
                "$summary · ${stringResolver.getString(
                    R.string.diagnostics_dpi_suite_quic_not_measured,
                    skippedTargetCount,
                )}"
            } else {
                summary
            }
        }

        is DpiSuiteProbeResult.EchReadiness -> {
            val ok = results.count { result -> result.verdict == EchProbeVerdict.ECH_OK }
            stringResolver.getString(R.string.diagnostics_dpi_suite_ech_format, ok, results.size)
        }

        is DpiSuiteProbeResult.Skipped -> {
            if (reason == "TCP16 detected zero blocked ASNs") {
                stringResolver.getString(R.string.diagnostics_dpi_suite_tcp16_no_blocked_asns)
            } else if (reason == DpiSuiteProbeResult.Skipped.NoValidTargetsReason) {
                stringResolver.getString(R.string.diagnostics_dpi_suite_no_valid_targets)
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
            quicTone()
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

private fun DpiSuiteProbeResult.QuicH3.quicTone(): DiagnosticsTone =
    when {
        results.none { it.verdict != QuicProbeVerdict.QUIC_INCONCLUSIVE } -> DiagnosticsTone.Neutral
        skippedTargetCount > 0 || results.any { it.verdict != QuicProbeVerdict.QUIC_OK } -> DiagnosticsTone.Warning
        else -> DiagnosticsTone.Positive
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
