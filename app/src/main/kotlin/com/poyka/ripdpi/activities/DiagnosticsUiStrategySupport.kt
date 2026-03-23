package com.poyka.ripdpi.activities

import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.displayLabel
import com.poyka.ripdpi.data.formatOffsetExpressionLabel
import com.poyka.ripdpi.data.strategyLaneFamilyLabel
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.deriveBypassStrategySignature
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import java.util.Locale
import com.poyka.ripdpi.diagnostics.displayLabel as displayStrategyLabel

internal fun DiagnosticsUiFactorySupport.toApproachDetailUiModel(
    detail: BypassApproachDetail,
): DiagnosticsApproachDetailUiModel =
    DiagnosticsApproachDetailUiModel(
        approach =
            toApproachRowUiModel(
                summary = detail.summary,
                mode =
                    when (detail.summary.approachId.kind) {
                        BypassApproachKind.Profile -> DiagnosticsApproachMode.Profiles
                        BypassApproachKind.Strategy -> DiagnosticsApproachMode.Strategies
                    },
            ),
        signature = buildList { detail.strategySignature?.let { addAll(strategySignatureFields(it)) } },
        breakdown =
            detail.summary.outcomeBreakdown.map { breakdown ->
                DiagnosticsMetricUiModel(
                    label = breakdown.probeType,
                    value = "${breakdown.successCount}/${breakdown.failureCount}",
                    tone =
                        when {
                            breakdown.failureCount > 0 -> DiagnosticsTone.Warning
                            breakdown.successCount > 0 -> DiagnosticsTone.Positive
                            else -> DiagnosticsTone.Neutral
                        },
                )
            },
        runtimeSummary =
            listOf(
                DiagnosticsMetricUiModel("Usage", detail.summary.usageCount.toString(), DiagnosticsTone.Info),
                DiagnosticsMetricUiModel(
                    "Runtime",
                    formatDurationMs(detail.summary.totalRuntimeDurationMs),
                    DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    "Errors",
                    detail.summary.recentRuntimeHealth.totalErrors
                        .toString(),
                    DiagnosticsTone.Warning,
                ),
                DiagnosticsMetricUiModel(
                    "Route changes",
                    detail.summary.recentRuntimeHealth.routeChanges
                        .toString(),
                    DiagnosticsTone.Info,
                ),
            ),
        recentSessions = detail.recentValidatedSessions.map(::toSessionRowUiModel),
        recentUsageNotes =
            detail.recentUsageSessions.map { usage ->
                "${usage.serviceMode} · ${usage.networkType} · ${formatDurationMs(
                    (usage.finishedAt ?: usage.startedAt) - usage.startedAt,
                )}"
            },
        failureNotes = detail.recentFailureNotes,
    )

internal fun DiagnosticsUiFactorySupport.toApproachRowUiModel(
    summary: BypassApproachSummary,
    mode: DiagnosticsApproachMode,
): DiagnosticsApproachRowUiModel =
    DiagnosticsApproachRowUiModel(
        id = summary.approachId.value,
        kind = mode,
        title = summary.displayName,
        subtitle = summary.secondaryLabel,
        verificationState = summary.verificationState.replaceFirstChar { it.uppercase() },
        lastValidatedResult = summary.lastValidatedResult ?: "Unverified",
        dominantFailurePattern = summary.topFailureOutcomes.firstOrNull() ?: "No dominant failure recorded",
        metrics =
            buildList {
                add(
                    DiagnosticsMetricUiModel(
                        label = "Validated",
                        value = summary.validatedScanCount.toString(),
                        tone = if (summary.validatedScanCount > 0) DiagnosticsTone.Info else DiagnosticsTone.Neutral,
                    ),
                )
                add(
                    DiagnosticsMetricUiModel(
                        label = "Success",
                        value = summary.validatedSuccessRate?.let { "${(it * 100).toInt()}%" } ?: "Unverified",
                        tone = summary.toDiagnosticsTone(),
                    ),
                )
                add(
                    DiagnosticsMetricUiModel(
                        label = "Usage",
                        value = summary.usageCount.toString(),
                        tone = DiagnosticsTone.Info,
                    ),
                )
                add(
                    DiagnosticsMetricUiModel(
                        label = "Runtime",
                        value = formatDurationMs(summary.totalRuntimeDurationMs),
                        tone = DiagnosticsTone.Neutral,
                    ),
                )
            },
        tone = summary.toDiagnosticsTone(),
    )

internal fun DiagnosticsUiFactorySupport.toStrategyProbeReportUiModel(
    report: StrategyProbeReport,
    reportResults: List<ProbeResult>,
    serviceMode: String?,
): DiagnosticsStrategyProbeReportUiModel {
    fun mapFamily(
        title: String,
        candidates: List<StrategyProbeCandidateSummary>,
        recommendedId: String,
    ): DiagnosticsStrategyProbeFamilyUiModel =
        DiagnosticsStrategyProbeFamilyUiModel(
            title = title,
            candidates =
                candidates
                    .map { candidate -> candidate.toCandidateUiModel(recommended = candidate.id == recommendedId) }
                    .sortedWith(
                        compareByDescending<DiagnosticsStrategyProbeCandidateUiModel> { it.recommended }
                            .thenBy { it.skipped }
                            .thenBy { it.label },
                    ),
        )

    val candidateDetails =
        (report.tcpCandidates + report.quicCandidates).associate { candidate ->
            candidate.id to
                toCandidateDetailUiModel(
                    candidate = candidate,
                    suiteId = report.suiteId,
                    serviceMode = serviceMode,
                    reportResults = reportResults,
                    recommended =
                        candidate.id == report.recommendation.tcpCandidateId ||
                            candidate.id == report.recommendation.quicCandidateId,
                )
        }

    return DiagnosticsStrategyProbeReportUiModel(
        suiteId = report.suiteId,
        suiteLabel = strategyProbeSuiteLabel(report.suiteId),
        summaryMetrics = buildStrategyProbeSummaryMetrics(report),
        recommendation = toStrategyProbeRecommendationUiModel(report.recommendation),
        families =
            listOf(
                mapFamily(
                    title =
                        if (report.suiteId == StrategyProbeSuiteFullMatrixV1) {
                            "TCP / HTTP / HTTPS matrix"
                        } else {
                            "TCP candidates"
                        },
                    candidates = report.tcpCandidates,
                    recommendedId = report.recommendation.tcpCandidateId,
                ),
                mapFamily(
                    title =
                        if (report.suiteId == StrategyProbeSuiteFullMatrixV1) {
                            "QUIC matrix"
                        } else {
                            "QUIC candidates"
                        },
                    candidates = report.quicCandidates,
                    recommendedId = report.recommendation.quicCandidateId,
                ),
            ),
        candidateDetails = candidateDetails,
    )
}

internal fun DiagnosticsUiFactorySupport.toResolverRecommendationUiModel(
    recommendation: ResolverRecommendation,
): DiagnosticsResolverRecommendationUiModel =
    DiagnosticsResolverRecommendationUiModel(
        headline = "Switch DNS to ${recommendation.selectedResolverId.replaceFirstChar { it.uppercase() }}",
        rationale = recommendation.rationale,
        fields =
            listOf(
                DiagnosticsFieldUiModel("Trigger", recommendation.triggerOutcome),
                DiagnosticsFieldUiModel("Resolver", recommendation.selectedResolverId),
                DiagnosticsFieldUiModel("Protocol", recommendation.selectedProtocol.uppercase()),
                DiagnosticsFieldUiModel("Endpoint", recommendation.selectedEndpoint),
                DiagnosticsFieldUiModel(
                    "Bootstrap",
                    recommendation.selectedBootstrapIps.joinToString().ifBlank { "None" },
                ),
            ),
        appliedTemporarily = recommendation.appliedTemporarily,
        persistable = recommendation.persistable,
    )

internal fun DiagnosticsUiFactorySupport.toScopeLabel(
    request: DiagnosticsProfileProjection?,
    rawArgsEnabled: Boolean,
): String? =
    when (request?.kind) {
        ScanKind.STRATEGY_PROBE -> {
            when {
                rawArgsEnabled && request.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1 -> {
                    "Automatic audit · raw-path only · blocked by command-line mode"
                }

                rawArgsEnabled -> {
                    "Automatic probing · raw-path only · blocked by command-line mode"
                }

                request.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1 -> {
                    "Automatic audit · raw-path only"
                }

                else -> {
                    "Automatic probing · raw-path only"
                }
            }
        }

        ScanKind.CONNECTIVITY -> {
            "Connectivity profile"
        }

        null -> {
            null
        }
    }

private fun DiagnosticsUiFactorySupport.strategySignatureFields(
    signature: BypassStrategySignature,
): List<DiagnosticsFieldUiModel> =
    buildList {
        add(DiagnosticsFieldUiModel("Mode", signature.mode))
        add(DiagnosticsFieldUiModel("Config source", signature.configSource))
        add(DiagnosticsFieldUiModel("Autolearn", signature.hostAutolearn))
        add(DiagnosticsFieldUiModel("Chain", signature.chainSummary))
        add(DiagnosticsFieldUiModel("Desync", signature.desyncMethod))
        signature.tcpStrategyFamily?.let {
            add(DiagnosticsFieldUiModel("TCP/TLS lane", strategyLaneFamilyLabel(it)))
        }
        signature.quicStrategyFamily?.let {
            add(DiagnosticsFieldUiModel("QUIC lane", strategyLaneFamilyLabel(it)))
        }
        signature.dnsStrategyLabel?.let {
            add(DiagnosticsFieldUiModel("DNS lane", it))
        }
        add(DiagnosticsFieldUiModel("Protocols", signature.protocolToggles.joinToString("/")))
        if (signature.httpParserEvasions.isNotEmpty()) {
            add(DiagnosticsFieldUiModel("HTTP parser evasions", formatHttpParserEvasions(signature.httpParserEvasions)))
        }
        add(DiagnosticsFieldUiModel("TLS record split", signature.tlsRecordSplitEnabled.toString()))
        signature.tlsRecordMarker?.let {
            add(DiagnosticsFieldUiModel("TLS record marker", formatOffsetExpressionLabel(it)))
        }
        signature.splitMarker?.let {
            add(DiagnosticsFieldUiModel("Split marker", formatOffsetExpressionLabel(it)))
        }
        signature.activationRound?.let {
            add(DiagnosticsFieldUiModel("Activation round", it))
        }
        signature.activationPayloadSize?.let {
            add(DiagnosticsFieldUiModel("Activation payload size", it))
        }
        signature.activationStreamBytes?.let {
            add(DiagnosticsFieldUiModel("Activation stream bytes", it))
        }
        signature.fakeTtlMode?.let {
            add(DiagnosticsFieldUiModel("Fake TTL mode", formatFakeTtlMode(it)))
        }
        signature.adaptiveFakeTtlWindow?.let {
            add(DiagnosticsFieldUiModel("Adaptive fake TTL window", it))
        }
        signature.adaptiveFakeTtlFallback?.let {
            add(DiagnosticsFieldUiModel("Adaptive fake TTL fallback", it.toString()))
        }
        signature.adaptiveFakeTtlBias?.let {
            add(DiagnosticsFieldUiModel("Adaptive fake TTL bias", formatAdaptiveFakeTtlBias(it)))
        }
        signature.fakeTlsBaseMode?.let {
            add(DiagnosticsFieldUiModel("Fake TLS base", formatFakeTlsBaseMode(it)))
        }
        signature.fakeSniMode?.let {
            add(
                DiagnosticsFieldUiModel(
                    "Fake TLS SNI",
                    formatFakeTlsSni(mode = it, fixedValue = signature.fakeSniValue),
                ),
            )
        }
        if (signature.fakeTlsMods.isNotEmpty()) {
            add(DiagnosticsFieldUiModel("Fake TLS mods", formatFakeTlsMods(signature.fakeTlsMods)))
        }
        signature.fakeTlsSize?.let {
            add(DiagnosticsFieldUiModel("Fake TLS size", formatFakeTlsSize(it)))
        }
        signature.httpFakeProfile?.let {
            add(DiagnosticsFieldUiModel("HTTP fake profile", formatHttpFakeProfile(it)))
        }
        signature.tlsFakeProfile?.let {
            add(DiagnosticsFieldUiModel("TLS fake profile", formatTlsFakeProfile(it)))
        }
        signature.udpFakeProfile?.let {
            add(DiagnosticsFieldUiModel("UDP fake profile", formatUdpFakeProfile(it)))
        }
        signature.fakePayloadSource?.let {
            add(DiagnosticsFieldUiModel("Fake payload source", formatFakePayloadSource(it)))
        }
        signature.quicFakeProfile?.let {
            add(DiagnosticsFieldUiModel("QUIC fake profile", formatQuicFakeProfile(it)))
        }
        signature.quicFakeHost?.let {
            add(DiagnosticsFieldUiModel("QUIC fake host", it))
        }
        signature.fakeOffsetMarker?.let {
            add(DiagnosticsFieldUiModel("Fake offset marker", it))
        }
        add(DiagnosticsFieldUiModel("Route group", signature.routeGroup ?: "Unknown"))
    }

private fun strategyProbeSuiteLabel(suiteId: String): String =
    when (suiteId) {
        StrategyProbeSuiteFullMatrixV1 -> "Automatic audit"
        StrategyProbeSuiteQuickV1 -> "Automatic probing"
        else -> suiteId.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

private fun strategyProbeOutcomeLabel(
    outcome: String,
    skipped: Boolean,
): String =
    when {
        skipped -> "Skipped"
        outcome.equals("not_applicable", ignoreCase = true) -> "Not applicable"
        else -> outcome.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

private fun strategyProbeCandidateTone(
    outcome: String,
    skipped: Boolean,
    recommended: Boolean,
): DiagnosticsTone =
    when {
        recommended -> DiagnosticsTone.Positive
        skipped -> DiagnosticsTone.Neutral
        outcome.equals("success", ignoreCase = true) -> DiagnosticsTone.Positive
        outcome.equals("partial", ignoreCase = true) -> DiagnosticsTone.Warning
        outcome.equals("not_applicable", ignoreCase = true) -> DiagnosticsTone.Neutral
        else -> DiagnosticsTone.Negative
    }

private fun strategyProbeFamilyLabel(family: String): String =
    when (family) {
        "baseline" -> "Baseline"
        "parser" -> "Parser"
        "parser_aggressive" -> "Parser aggressive"
        "split" -> "Host split"
        "tlsrec_split" -> "TLS record split"
        "tlsrec_fake" -> "TLS record fake"
        "fake_approx" -> "Fake approximation"
        "hostfake" -> "Hostfake"
        "activation_window" -> "Activation window"
        "adaptive_fake_ttl" -> "Adaptive fake TTL"
        "fake_payload_library" -> "Fake payload library"
        "quic_disabled" -> "QUIC disabled"
        "quic_burst" -> "QUIC burst"
        else -> family.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

private fun parseServiceModeOrDefault(serviceMode: String?): Mode =
    when (serviceMode?.uppercase(Locale.US)) {
        "PROXY" -> Mode.Proxy
        "VPN" -> Mode.VPN
        else -> Mode.VPN
    }

private fun buildStrategyProbeCandidateMetrics(
    summary: StrategyProbeCandidateSummary,
    recommended: Boolean,
): List<DiagnosticsMetricUiModel> =
    buildList {
        add(DiagnosticsMetricUiModel("Targets", "${summary.succeededTargets}/${summary.totalTargets}"))
        add(DiagnosticsMetricUiModel("Weight", "${summary.weightedSuccessScore}/${summary.totalWeight}"))
        add(
            DiagnosticsMetricUiModel(
                "Quality",
                summary.qualityScore.toString(),
                tone =
                    when {
                        summary.qualityScore >= summary.totalTargets.coerceAtLeast(1) * 3 -> DiagnosticsTone.Positive
                        summary.qualityScore > 0 -> DiagnosticsTone.Warning
                        else -> DiagnosticsTone.Neutral
                    },
            ),
        )
        summary.averageLatencyMs?.let {
            add(DiagnosticsMetricUiModel("Latency", "$it ms", DiagnosticsTone.Info))
        }
        if (recommended) {
            add(DiagnosticsMetricUiModel("Selected", "Winner", DiagnosticsTone.Positive))
        }
    }

private fun DiagnosticsUiFactorySupport.deriveSignature(
    summary: StrategyProbeCandidateSummary,
    serviceMode: String?,
): BypassStrategySignature? =
    summary.proxyConfigJson
        ?.takeIf { it.isNotBlank() }
        ?.let(::decodeRipDpiProxyUiPreferences)
        ?.let { preferences ->
            deriveBypassStrategySignature(
                preferences = preferences,
                routeGroup = null,
                modeOverride = parseServiceModeOrDefault(serviceMode),
            )
        }

private fun ProbeResult.detailValue(key: String): String? = details.firstOrNull { it.key == key }?.value

private fun DiagnosticsUiFactorySupport.buildStrategyProbeResultGroups(
    reportResults: List<ProbeResult>,
    candidateId: String,
): List<DiagnosticsProbeGroupUiModel> =
    reportResults
        .filter { result -> result.detailValue("candidateId") == candidateId }
        .mapIndexed { index, result ->
            toProbeResultUiModel(
                index = index,
                pathMode = ScanPathMode.RAW_PATH,
                result = result,
            )
        }
        .groupBy { probe ->
            probe.details
                .firstOrNull { it.label == "protocol" }
                ?.value
                ?.uppercase(Locale.US)
                ?.let { "$it results" }
                ?: probe.probeType.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }.map { (title, items) ->
            DiagnosticsProbeGroupUiModel(title = title, items = items)
        }.sortedBy { it.title }

private fun StrategyProbeCandidateSummary.toCandidateUiModel(
    recommended: Boolean,
): DiagnosticsStrategyProbeCandidateUiModel =
    DiagnosticsStrategyProbeCandidateUiModel(
        id = id,
        label = label,
        outcome = strategyProbeOutcomeLabel(outcome = outcome, skipped = skipped),
        rationale = rationale,
        metrics = buildStrategyProbeCandidateMetrics(this, recommended),
        tone = strategyProbeCandidateTone(outcome = outcome, skipped = skipped, recommended = recommended),
        skipped = skipped,
        recommended = recommended,
    )

private fun DiagnosticsUiFactorySupport.toCandidateDetailUiModel(
    candidate: StrategyProbeCandidateSummary,
    suiteId: String,
    serviceMode: String?,
    reportResults: List<ProbeResult>,
    recommended: Boolean,
): DiagnosticsStrategyProbeCandidateDetailUiModel =
    DiagnosticsStrategyProbeCandidateDetailUiModel(
        id = candidate.id,
        label = candidate.label,
        familyLabel = strategyProbeFamilyLabel(candidate.family),
        suiteLabel = strategyProbeSuiteLabel(suiteId),
        outcome = strategyProbeOutcomeLabel(outcome = candidate.outcome, skipped = candidate.skipped),
        rationale = candidate.rationale,
        tone =
            strategyProbeCandidateTone(
                outcome = candidate.outcome,
                skipped = candidate.skipped,
                recommended = recommended,
            ),
        recommended = recommended,
        notes = candidate.notes,
        metrics = buildStrategyProbeCandidateMetrics(candidate, recommended),
        signature = deriveSignature(candidate, serviceMode)?.let(::strategySignatureFields).orEmpty(),
        resultGroups = buildStrategyProbeResultGroups(reportResults = reportResults, candidateId = candidate.id),
    )

private fun DiagnosticsUiFactorySupport.toStrategyProbeRecommendationUiModel(
    recommendation: StrategyProbeRecommendation,
): DiagnosticsStrategyProbeRecommendationUiModel =
    DiagnosticsStrategyProbeRecommendationUiModel(
        headline =
            listOfNotNull(
                recommendation.tcpCandidateLabel,
                recommendation.quicCandidateLabel,
                recommendation.dnsStrategyLabel,
            ).joinToString(" + "),
        rationale = recommendation.rationale,
        fields =
            listOf(
                DiagnosticsFieldUiModel("TCP recommendation", recommendation.tcpCandidateLabel),
                recommendation.tcpCandidateFamily?.let {
                    DiagnosticsFieldUiModel("TCP/TLS lane", strategyProbeFamilyLabel(it))
                },
                DiagnosticsFieldUiModel("QUIC recommendation", recommendation.quicCandidateLabel),
                recommendation.quicCandidateFamily?.let {
                    DiagnosticsFieldUiModel("QUIC lane", strategyProbeFamilyLabel(it))
                },
                recommendation.dnsStrategyLabel?.let {
                    DiagnosticsFieldUiModel("DNS lane", it)
                },
                DiagnosticsFieldUiModel("Why it won", recommendation.rationale),
            ).filterNotNull(),
        signature = recommendation.strategySignature?.let { signature -> strategySignatureFields(signature) }.orEmpty(),
    )

private fun buildStrategyProbeSummaryMetrics(report: StrategyProbeReport): List<DiagnosticsMetricUiModel> {
    val candidates = report.tcpCandidates + report.quicCandidates
    val worked = candidates.count { it.outcome.equals("success", ignoreCase = true) }
    val partial = candidates.count { it.outcome.equals("partial", ignoreCase = true) }
    val failed =
        candidates.count { candidate ->
            !candidate.skipped &&
                !candidate.outcome.equals("success", ignoreCase = true) &&
                !candidate.outcome.equals("partial", ignoreCase = true) &&
                !candidate.outcome.equals("not_applicable", ignoreCase = true)
        }
    val notApplicable = candidates.count { it.outcome.equals("not_applicable", ignoreCase = true) }
    val skipped = candidates.count { it.skipped }
    return buildList {
        add(DiagnosticsMetricUiModel("Worked", worked.toString(), DiagnosticsTone.Positive))
        add(DiagnosticsMetricUiModel("Partial", partial.toString(), DiagnosticsTone.Warning))
        add(DiagnosticsMetricUiModel("Failed", failed.toString(), DiagnosticsTone.Negative))
        add(DiagnosticsMetricUiModel("N/A", notApplicable.toString(), DiagnosticsTone.Neutral))
        if (skipped > 0) {
            add(DiagnosticsMetricUiModel("Skipped", skipped.toString(), DiagnosticsTone.Neutral))
        }
    }
}

private fun formatFakeTlsBaseMode(value: String): String =
    when (value.lowercase(Locale.US)) {
        "default" -> "Default fake ClientHello"
        "original" -> "Original ClientHello"
        else -> value
    }

private fun formatFakeTtlMode(value: String): String =
    when (value.lowercase(Locale.US)) {
        "fixed" -> "Fixed TTL"
        "adaptive" -> "Adaptive TTL"
        "adaptive_custom" -> "Custom adaptive TTL"
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }

private fun formatAdaptiveFakeTtlBias(value: Int): String =
    when {
        value < 0 -> "Prefer lower TTLs first ($value)"
        value > 0 -> "Prefer higher TTLs first (+$value)"
        else -> "Alternate around the seed (0)"
    }

private fun formatFakeTlsSni(
    mode: String,
    fixedValue: String?,
): String =
    when (mode.lowercase(Locale.US)) {
        "fixed" -> fixedValue?.takeIf { it.isNotBlank() }?.let { "Fixed ($it)" } ?: "Fixed"
        "randomized" -> "Randomized"
        else -> mode
    }

private fun formatFakeTlsMods(values: List<String>): String =
    values.joinToString(", ") { value ->
        when (value.lowercase(Locale.US)) {
            "rand" -> "Randomize TLS material"
            "dupsid" -> "Copy Session ID"
            "padencap" -> "Padding camouflage"
            else -> value
        }
    }

private fun formatFakeTlsSize(value: Int): String =
    when {
        value > 0 -> "Exactly $value bytes"
        value < 0 -> "Input minus ${-value} bytes"
        else -> "Match input size"
    }

private fun formatHttpFakeProfile(value: String): String =
    when (value.lowercase(Locale.US)) {
        "compat_default" -> "Compatibility default"
        "iana_get" -> "IANA GET"
        "cloudflare_get" -> "Cloudflare GET"
        else -> value
    }

private fun formatHttpParserEvasions(values: List<String>): String =
    values.joinToString(", ") { value ->
        when (value.lowercase(Locale.US)) {
            "host_mixed_case" -> "Host mixed case"
            "domain_mixed_case" -> "Domain mixed case"
            "host_remove_spaces" -> "Host remove spaces"
            "method_eol" -> "Method EOL shift"
            "unix_eol" -> "Unix line endings"
            else -> value
        }
    }

private fun formatTlsFakeProfile(value: String): String =
    when (value.lowercase(Locale.US)) {
        "compat_default" -> "Compatibility default"
        "iana_firefox" -> "IANA Firefox"
        "google_chrome" -> "Google Chrome"
        "vk_chrome" -> "VK Chrome"
        "sberbank_chrome" -> "Sberbank Chrome"
        "rutracker_kyber" -> "Rutracker Kyber"
        "bigsize_iana" -> "IANA bigsize"
        else -> value
    }

private fun formatUdpFakeProfile(value: String): String =
    when (value.lowercase(Locale.US)) {
        "compat_default" -> "Compatibility default"
        "zero_256" -> "Zero blob 256"
        "zero_512" -> "Zero blob 512"
        "dns_query" -> "DNS query"
        "stun_binding" -> "STUN binding"
        "wireguard_initiation" -> "WireGuard initiation"
        "dht_get_peers" -> "DHT get_peers"
        else -> value
    }

private fun formatFakePayloadSource(value: String): String =
    when (value.lowercase(Locale.US)) {
        "custom_raw" -> "Custom raw fake payload"
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }

private fun formatQuicFakeProfile(value: String): String =
    when (value.lowercase(Locale.US)) {
        "compat_default" -> "Compatibility blob"
        "realistic_initial" -> "Realistic Initial"
        "disabled" -> "Off"
        else -> value
    }
