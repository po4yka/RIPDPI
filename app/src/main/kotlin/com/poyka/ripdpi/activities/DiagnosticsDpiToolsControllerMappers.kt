package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTlsClientState
import com.poyka.ripdpi.diagnostics.dpi.AttemptResult
import com.poyka.ripdpi.diagnostics.dpi.DnsServerResult
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityResult
import com.poyka.ripdpi.diagnostics.dpi.DomainVerdict
import com.poyka.ripdpi.diagnostics.dpi.Tcp16AsnSummary
import com.poyka.ripdpi.diagnostics.dpi.Tcp16ProbeResult
import com.poyka.ripdpi.diagnostics.dpi.Tcp16Verdict
import com.poyka.ripdpi.diagnostics.dpi.byAsn
import com.poyka.ripdpi.diagnostics.dpich.CompressionCodec
import com.poyka.ripdpi.diagnostics.dpich.CompressionProbeResult
import com.poyka.ripdpi.diagnostics.dpich.CompressionProbeVerdict
import com.poyka.ripdpi.diagnostics.rkn.RknAggregateVerdictEngine
import com.poyka.ripdpi.diagnostics.rkn.RknCheckResult
import com.poyka.ripdpi.diagnostics.rkn.RknConfidence
import com.poyka.ripdpi.diagnostics.rkn.RknProbeTarget
import com.poyka.ripdpi.diagnostics.rkn.RknTarget
import com.poyka.ripdpi.diagnostics.rkn.RknVerdict
import com.poyka.ripdpi.diagnostics.rkn.RknVerdictColorToken
import com.poyka.ripdpi.diagnostics.rkn.RknVerdictLabel
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoResult
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toPersistentList
import java.util.Locale

internal data class RknDiagnosisRunResult(
    val controlResults: List<RknCheckResult>,
    val testResults: List<RknCheckResult>,
    val selfInfo: SelfInfoResult?,
    val fetchSelfInfoEnabled: Boolean,
    val selfInfoPrivacyOverridden: Boolean,
)

internal data class CompressionProbeRunResult(
    val targetUrl: String,
    val includeZstd: Boolean,
    val results: Map<CompressionCodec, CompressionProbeResult>,
)

internal fun List<DnsServerResult>.toDnsAvailabilityUiModel(
    stringResolver: StringResolver,
): DiagnosticsDnsAvailabilityToolUiModel {
    val available = count { result -> result.availableDomains > 0 }
    return DiagnosticsDnsAvailabilityToolUiModel(
        state = DiagnosticsDnsAvailabilityState.Complete,
        summary = stringResolver.getString(R.string.diagnostics_dns_availability_complete, available, size),
        metrics =
            listOf(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_dns_availability_metric_servers),
                    size.toString(),
                    DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_dns_availability_metric_available),
                    available.toString(),
                    countTone(size - available),
                ),
            ).toPersistentList(),
        rows =
            map { result ->
                DiagnosticsDnsAvailabilityServerUiModel(
                    name = result.name,
                    type = result.type.name.lowercase(Locale.US),
                    availability = "${result.availableDomains}/${result.totalDomains}",
                    latency =
                        result.avgLatencyMs?.let { "$it ms" }
                            ?: stringResolver.getString(R.string.diagnostics_value_timeout),
                    tone = if (result.availableDomains > 0) DiagnosticsTone.Positive else DiagnosticsTone.Warning,
                )
            }.toPersistentList(),
    )
}

internal fun List<DomainReachabilityResult>.toUiModel(
    stubIpCount: Int,
    stringResolver: StringResolver,
): DiagnosticsDomainReachabilityToolUiModel {
    val blocked = count { result -> result.verdict != DomainVerdict.OK }
    val checked = size
    return DiagnosticsDomainReachabilityToolUiModel(
        state = DiagnosticsDomainReachabilityState.Complete,
        summary =
            if (blocked == 0) {
                stringResolver.getString(R.string.diagnostics_domain_reachability_complete_clean, checked)
            } else {
                stringResolver.getString(R.string.diagnostics_domain_reachability_complete_flagged, blocked, checked)
            },
        metrics =
            (
                listOf(
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_domain_reachability_metric_checked),
                        checked.toString(),
                        DiagnosticsTone.Info,
                    ),
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_domain_reachability_metric_flagged),
                        blocked.toString(),
                        countTone(blocked),
                    ),
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_domain_reachability_metric_stub_ips),
                        stubIpCount.toString(),
                        DiagnosticsTone.Neutral,
                    ),
                ) + mapNotNull { result -> result.tlsClientState }.toDiagnosticsTlsMetrics(stringResolver)
            ).toPersistentList(),
        rows =
            map { result ->
                DiagnosticsDomainReachabilityDomainUiModel(
                    domain = result.domain,
                    verdict = result.verdict.displayLabel(),
                    resolvedIps =
                        result.resolvedIps.joinToString().ifBlank {
                            stringResolver.getString(R.string.diagnostics_value_unresolved)
                        },
                    tls13 = result.tls13.displayLabel(),
                    tls12 = result.tls12.displayLabel(),
                    http = result.http.displayLabel(),
                    tone = result.verdict.tone(),
                )
            }.toPersistentList(),
    )
}

internal fun CompressionProbeRunResult.toUiModel(
    stringResolver: StringResolver,
): DiagnosticsCompressionProbeToolUiModel {
    val okCount = results.values.count { result -> result.verdict == CompressionProbeVerdict.OK }
    val naValue = stringResolver.getString(R.string.diagnostics_value_na)
    return DiagnosticsCompressionProbeToolUiModel(
        state = DiagnosticsCompressionProbeState.Complete,
        targetUrl = targetUrl,
        summary =
            stringResolver.getString(
                R.string.diagnostics_compression_complete,
                okCount,
                results.size,
                targetUrl,
            ),
        includeZstd = includeZstd,
        metrics =
            listOf(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_compression_metric_target),
                    targetUrl,
                    DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_compression_metric_usable),
                    okCount.toString(),
                    countTone(results.size - okCount),
                ),
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_compression_metric_zstd),
                    stringResolver.getString(
                        if (includeZstd) R.string.diagnostics_value_enabled else R.string.diagnostics_value_off,
                    ),
                    DiagnosticsTone.Neutral,
                ),
            ).toPersistentList(),
        rows =
            CompressionCodec.entries
                .map { codec ->
                    val result = results.getValue(codec)
                    DiagnosticsCompressionCodecUiModel(
                        codec = codec.name.lowercase(Locale.US),
                        verdict = result.verdict.displayLabel(),
                        compressedBytes = result.compressedBytes?.toString() ?: naValue,
                        decompressedBytes = result.decompressedBytes?.toString() ?: naValue,
                        tone = result.verdict.tone(),
                    )
                }.toPersistentList(),
    )
}

internal fun List<Tcp16ProbeResult>.toTcp16UiModel(
    stringResolver: StringResolver,
): DiagnosticsTcp16FatHeaderToolUiModel {
    val detected = count { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
    val invalid = count { result -> result.verdict == Tcp16Verdict.INVALID_RECONNECTED }
    val unavailable = count { result -> result.verdict == Tcp16Verdict.DEAD || result.verdict == Tcp16Verdict.ERROR }
    return DiagnosticsTcp16FatHeaderToolUiModel(
        state = DiagnosticsTcp16FatHeaderState.Complete,
        summary =
            if (detected == 0 && invalid == 0) {
                stringResolver.getString(R.string.diagnostics_tcp16_complete_clean, size)
            } else {
                stringResolver.getString(R.string.diagnostics_tcp16_complete_detected, detected, invalid)
            },
        metrics =
            (
                listOf(
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_tcp16_metric_targets),
                        size.toString(),
                        DiagnosticsTone.Info,
                    ),
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_tcp16_metric_detected),
                        detected.toString(),
                        countTone(detected),
                    ),
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_tcp16_metric_unavailable),
                        unavailable.toString(),
                        DiagnosticsTone.Neutral,
                    ),
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_tcp16_metric_single_socket_invalid),
                        invalid.toString(),
                        countTone(invalid),
                    ),
                ) + mapNotNull { result -> result.tlsClientState }.toDiagnosticsTlsMetrics(stringResolver)
            ).toPersistentList(),
        rows =
            byAsn()
                .values
                .sortedWith(compareByDescending<Tcp16AsnSummary> { summary -> summary.detected }.thenBy { it.asn })
                .map { summary -> summary.toUiModel() }
                .toPersistentList(),
        detectedResults =
            filter { result -> result.verdict == Tcp16Verdict.DETECTED_AT_KB }
                .map { result ->
                    DiagnosticsTcp16DetectedTargetUiModel(
                        targetId = result.targetId,
                        asn = result.asn,
                        provider = result.provider,
                        ip = result.ip,
                    )
                }.toPersistentList(),
    )
}

private fun Tcp16AsnSummary.toUiModel(): DiagnosticsTcp16AsnUiModel {
    val warningCount = detected + errors + invalidReconnected
    return DiagnosticsTcp16AsnUiModel(
        asn = asn,
        providers = providers.joinToString(),
        checked = checked.toString(),
        detected = detected.toString(),
        dead = dead.toString(),
        errors = (errors + invalidReconnected).toString(),
        tone = countTone(warningCount),
    )
}

internal fun buildRknBlockDiagnosisUiModel(
    result: RknDiagnosisRunResult,
    stringResolver: StringResolver,
): DiagnosticsRknBlockDiagnosisToolUiModel {
    val controlResults = result.controlResults
    val testResults = result.testResults
    val aggregate = RknAggregateVerdictEngine.aggregate(controlResults, testResults)
    val blockTypes = RknAggregateVerdictEngine.blockTypes(testResults)
    val flagged = testResults.count { result -> result.verdict != RknVerdict.OK }
    val controlGroup = stringResolver.getString(R.string.diagnostics_rkn_group_control)
    val testGroup = stringResolver.getString(R.string.diagnostics_rkn_group_test)
    val rows =
        controlResults.map { result -> result.toUiModel(controlGroup) } +
            testResults.map { result -> result.toUiModel(testGroup) }
    return DiagnosticsRknBlockDiagnosisToolUiModel(
        state = DiagnosticsRknBlockDiagnosisState.Complete,
        headline = aggregate.headline,
        confidenceNote = aggregate.confidenceNote,
        summary =
            stringResolver.getString(
                R.string.diagnostics_rkn_complete,
                controlResults.size,
                testResults.size,
            ),
        fetchSelfInfoEnabled = result.fetchSelfInfoEnabled,
        selfInfoPrivacyOverridden = result.selfInfoPrivacyOverridden,
        selfInfo = result.selfInfo?.toUiModel(stringResolver),
        metrics =
            (
                listOf(
                    DiagnosticsMetricUiModel(controlGroup, controlResults.size.toString(), DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel(testGroup, testResults.size.toString(), DiagnosticsTone.Info),
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_rkn_metric_flagged),
                        flagged.toString(),
                        countTone(flagged),
                    ),
                ) +
                    (controlResults + testResults)
                        .mapNotNull { item -> item.tlsClientState }
                        .toDiagnosticsTlsMetrics(stringResolver)
            ).toPersistentList(),
        blockTypes =
            blockTypes
                .map { (verdict, count) ->
                    val label = RknVerdictLabel.format(verdict, RknConfidence.HIGH)
                    DiagnosticsRknBlockTypeUiModel(
                        label = "${label.symbol} ${label.text}",
                        count = count,
                        tone = label.color.toDiagnosticsTone(),
                    )
                }.toPersistentList(),
        rows = rows.toPersistentList(),
    )
}

private fun SelfInfoResult.toUiModel(stringResolver: StringResolver): DiagnosticsRknSelfInfoUiModel =
    DiagnosticsRknSelfInfoUiModel(
        maskedIp = SelfInfoFetcher.maskIpForDisplay(ip),
        provider =
            listOfNotNull(asn, org).joinToString(" ").ifBlank {
                stringResolver.getString(R.string.diagnostics_rkn_unknown_isp)
            },
        asn = asn,
        org = org,
        location = listOfNotNull(city, region, country).joinToString(", ").ifBlank { null },
        source = source,
    )

private fun DomainVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun CompressionProbeVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun AttemptResult.displayLabel(): String =
    buildList {
        add(status.name.lowercase(Locale.US).replace('_', ' '))
        statusCode?.let { code -> add(code.toString()) }
        error?.let { error -> add(error.label.lowercase(Locale.US).replace('_', ' ')) }
    }.joinToString(" · ")

internal fun List<RknTarget>.toProbeTargets(): List<RknProbeTarget> =
    map { target -> RknProbeTarget(name = target.name, url = target.url) }

private fun RknCheckResult.toUiModel(group: String): DiagnosticsRknTargetUiModel {
    val label = RknVerdictLabel.format(verdict, confidence)
    return DiagnosticsRknTargetUiModel(
        group = group,
        name = name,
        verdict = "${label.symbol} ${label.text}",
        notes = notes.ifEmpty { listOfNotNull(dnsError, tcpError, tlsError, httpError) }.joinToString("; "),
        tone = label.color.toDiagnosticsTone(),
    )
}

private fun RknVerdictColorToken.toDiagnosticsTone(): DiagnosticsTone =
    when (this) {
        RknVerdictColorToken.POSITIVE -> DiagnosticsTone.Positive
        RknVerdictColorToken.WARNING -> DiagnosticsTone.Warning
        RknVerdictColorToken.ERROR -> DiagnosticsTone.Negative
        RknVerdictColorToken.MUTED -> DiagnosticsTone.Neutral
    }

private fun List<DiagnosticsTlsClientState>.toDiagnosticsTlsMetrics(
    stringResolver: StringResolver,
): List<DiagnosticsMetricUiModel> {
    val state = firstOrNull() ?: return emptyList()
    val mode =
        when {
            state.nativeOwnedTlsAvailable -> stringResolver.getString(R.string.diagnostics_tls_mode_native_owned)
            state.fallbackActive -> stringResolver.getString(R.string.diagnostics_tls_mode_android_fallback)
            else -> stringResolver.getString(R.string.diagnostics_tls_mode_default)
        }
    val tone =
        when {
            state.fallbackActive -> DiagnosticsTone.Warning
            state.nativeOwnedTlsAvailable -> DiagnosticsTone.Positive
            else -> DiagnosticsTone.Neutral
        }
    return buildList {
        state.profileId?.let { profile ->
            add(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_tls_metric_profile),
                    profile,
                    DiagnosticsTone.Info,
                ),
            )
        }
        add(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_tls_metric_mode),
                mode,
                tone,
            ),
        )
    }
}

private fun countTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }

private fun DomainVerdict.tone(): DiagnosticsTone =
    when (this) {
        DomainVerdict.OK -> DiagnosticsTone.Positive

        DomainVerdict.DNS_FAIL,
        DomainVerdict.FAKE_IP,
        -> DiagnosticsTone.Neutral

        DomainVerdict.BLOCKED,
        DomainVerdict.TLS_VERSION_BLOCK,
        DomainVerdict.ISP_PAGE,
        DomainVerdict.TCP16_BAND,
        DomainVerdict.UNREACHABLE,
        -> DiagnosticsTone.Warning
    }

private fun CompressionProbeVerdict.tone(): DiagnosticsTone =
    when (this) {
        CompressionProbeVerdict.OK -> DiagnosticsTone.Positive

        CompressionProbeVerdict.NOT_SUPPORTED -> DiagnosticsTone.Neutral

        CompressionProbeVerdict.EOF_BEFORE_MIN,
        CompressionProbeVerdict.TIMEOUT,
        CompressionProbeVerdict.CONN_ERR,
        CompressionProbeVerdict.INTERNAL_ERR,
        -> DiagnosticsTone.Warning
    }
