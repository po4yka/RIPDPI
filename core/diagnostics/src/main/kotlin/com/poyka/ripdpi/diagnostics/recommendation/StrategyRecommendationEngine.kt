@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

private const val ProbeTypeTcpFatHeader = "tcp_fat_header"
private const val ProbeTypeDomainReachability = "domain_reachability"
private const val ProbeTypeServiceReachability = "service_reachability"
private const val ProbeTypeCircumventionReachability = "circumvention_reachability"
private const val ProbeTypeThroughputWindow = "throughput_window"
private const val ProbeTypeQuicReachability = "quic_reachability"

private const val OutcomeTcpReset = "tcp_reset"
private const val OutcomeTcpTimeout = "tcp_timeout"
private const val OutcomeTcp16kbBlocked = "tcp_16kb_blocked"
private const val OutcomeTlsHandshakeFailed = "tls_handshake_failed"
private const val OutcomeUnreachable = "unreachable"
private const val OutcomeServiceBlocked = "service_blocked"
private const val OutcomeCircumventionBlocked = "circumvention_blocked"
private const val OutcomeThroughputFailed = "throughput_failed"
private const val OutcomeQuicError = "quic_error"

private const val PatternRstInjection = "rst_injection"
private const val PatternThresholdBlocking = "threshold_blocking"
private const val PatternSilentDrop = "silent_drop"
private const val PatternMixedDpi = "mixed_dpi"

private const val FamilyFake = "fake"
private const val FamilyDisorder = "disorder"
private const val FamilyTlsrecFake = "tlsrec_fake"

private const val MinTcpFailuresForRecommendation = 2
private const val MinRawPathRecommendationStrength = 2
private const val MaxEvidenceTargets = 3
private const val RealReachabilitySignalWeight = 2
private const val SyntheticTcpSignalWeight = 1
private const val HighConfidenceEvidenceScore = 4
private const val MinRealReachabilitySignalsForMediumConfidence = 1

internal object StrategyRecommendationEngine {
    private val outcomeToCategory: Map<Pair<String, String>, SignalCategory> =
        mapOf(
            (ProbeTypeTcpFatHeader to OutcomeTcpReset) to SignalCategory.TCP_RST,
            (ProbeTypeTcpFatHeader to OutcomeTcp16kbBlocked) to SignalCategory.THRESHOLD_BLOCK,
            (ProbeTypeTcpFatHeader to OutcomeTcpTimeout) to SignalCategory.SILENT_DROP,
            (ProbeTypeTcpFatHeader to OutcomeTlsHandshakeFailed) to SignalCategory.TLS_INTERFERENCE,
            (ProbeTypeDomainReachability to OutcomeUnreachable) to SignalCategory.DOMAIN_BLOCKED,
            (ProbeTypeServiceReachability to OutcomeServiceBlocked) to SignalCategory.SERVICE_BLOCKED,
            (ProbeTypeCircumventionReachability to OutcomeCircumventionBlocked) to SignalCategory.CIRCUMVENTION_BLOCKED,
            (ProbeTypeThroughputWindow to OutcomeThroughputFailed) to SignalCategory.THROUGHPUT_BLOCKED,
            (ProbeTypeQuicReachability to OutcomeQuicError) to SignalCategory.QUIC_BLOCKED,
        )

    fun compute(
        report: ScanReport,
        currentTcpFamily: String?,
    ): StrategyRecommendation? {
        report.confirmGoodDpiVerdict?.let { verdict ->
            return StrategyRecommendation(
                triggerOutcomes = listOf("confirm_good_dpi_suspected"),
                recommendedFamily = "udp_quic",
                blockingPattern = "post_handshake_behavioral_block",
                rationale =
                    "Reality handshakes completed on ${verdict.evidence.distinctTargetCount} distinct targets " +
                        "without application data while QUIC succeeded.",
                evidence =
                    listOf(
                        "stalledRealityFlows=${verdict.evidence.stalledFlowCount}",
                        "quicControlSucceeded=${verdict.evidence.quicControlSucceeded}",
                    ),
                actionable = true,
                confidence = StrategyRecommendationConfidence.HIGH,
                evidenceScore = HighConfidenceEvidenceScore,
            )
        }
        val signals = collectBlockingSignals(report.results)
        return if (signals.isEmpty() || !report.hasActionableStrategyEvidence(signals)) {
            null
        } else {
            val pattern = classifyBlockingPattern(signals)
            val recommendedFamily =
                recommendFamily(pattern, currentTcpFamily)
                    ?.takeIf { it != currentTcpFamily }
            recommendedFamily?.let { family ->
                val evidence = buildEvidence(signals)
                val evidenceScore = signals.sumOf { it.recommendationStrength() }
                val realReachabilityCount = signals.count { it.isRealReachabilityFailure() }
                val confidence =
                    when {
                        realReachabilityCount >= MinTcpFailuresForRecommendation &&
                            evidenceScore >= HighConfidenceEvidenceScore -> {
                            StrategyRecommendationConfidence.HIGH
                        }

                        realReachabilityCount >= MinRealReachabilitySignalsForMediumConfidence &&
                            evidenceScore >= MinRawPathRecommendationStrength -> {
                            StrategyRecommendationConfidence.MEDIUM
                        }

                        else -> {
                            StrategyRecommendationConfidence.LOW
                        }
                    }
                StrategyRecommendation(
                    triggerOutcomes = signals.map { it.outcome }.distinct(),
                    recommendedFamily = family,
                    blockingPattern = pattern,
                    rationale = buildRationale(pattern, family, signals),
                    evidence = evidence,
                    actionable = true,
                    confidence = confidence,
                    evidenceScore = evidenceScore,
                )
            }
        }
    }

    private fun collectBlockingSignals(results: List<ProbeResult>): List<BlockingSignal> =
        buildList {
            for (result in results) {
                val signal = classifyResult(result)
                if (signal != null) add(signal)
            }
        }

    private fun classifyResult(result: ProbeResult): BlockingSignal? {
        val category = outcomeToCategory[result.probeType to result.outcome] ?: return null
        return BlockingSignal(
            probeType = result.probeType,
            target = result.target,
            outcome = result.outcome,
            category = category,
        )
    }

    private fun classifyBlockingPattern(signals: List<BlockingSignal>): String {
        val categories = signals.map { it.category }.toSet()
        val tcpRstCount = signals.count { it.category == SignalCategory.TCP_RST }
        val thresholdCount = signals.count { it.category == SignalCategory.THRESHOLD_BLOCK }

        return when {
            tcpRstCount >= MinTcpFailuresForRecommendation &&
                thresholdCount == 0 -> PatternRstInjection

            thresholdCount >= 1 -> PatternThresholdBlocking

            categories.contains(SignalCategory.SILENT_DROP) &&
                !categories.contains(SignalCategory.TCP_RST) -> PatternSilentDrop

            else -> PatternMixedDpi
        }
    }

    private fun recommendFamily(
        pattern: String,
        currentFamily: String?,
    ): String? {
        val basicFamilies = setOf("split", "split_host", null, "")
        val isBasicStrategy = currentFamily in basicFamilies

        return when (pattern) {
            PatternRstInjection -> {
                // RST injection: DPI injects TCP RST. Fake packets with TTL trick defeat this.
                if (isBasicStrategy || currentFamily == FamilyDisorder) FamilyFake else FamilyTlsrecFake
            }

            PatternThresholdBlocking -> {
                // Threshold blocking: DPI allows handshake but blocks after N KB.
                // Disorder breaks reassembly. TLS record split + fake is most reliable.
                if (isBasicStrategy) FamilyTlsrecFake else FamilyDisorder
            }

            PatternSilentDrop -> {
                // Silent drop: packets disappear. Disorder with TTL trick.
                if (isBasicStrategy) FamilyDisorder else FamilyTlsrecFake
            }

            PatternMixedDpi -> {
                // Mixed signals: TLS record split + fake is the most broadly effective.
                if (isBasicStrategy) FamilyTlsrecFake else null
            }

            else -> {
                null
            }
        }
    }

    private fun buildEvidence(signals: List<BlockingSignal>): List<String> =
        signals
            .groupBy { it.category }
            .map { (category, grouped) ->
                val targets = grouped.map { it.target }.distinct().take(MaxEvidenceTargets)
                val targetSuffix =
                    if (targets.size < grouped.size) " (+${grouped.size - targets.size} more)" else ""
                "${category.label}: ${targets.joinToString(", ")}$targetSuffix"
            }

    private fun buildRationale(
        pattern: String,
        family: String,
        signals: List<BlockingSignal>,
    ): String {
        val tcpCount =
            signals.count {
                it.category in
                    setOf(
                        SignalCategory.TCP_RST,
                        SignalCategory.THRESHOLD_BLOCK,
                        SignalCategory.SILENT_DROP,
                        SignalCategory.TLS_INTERFERENCE,
                    )
            }
        val serviceCount =
            signals.count {
                it.category in
                    setOf(
                        SignalCategory.DOMAIN_BLOCKED,
                        SignalCategory.SERVICE_BLOCKED,
                        SignalCategory.CIRCUMVENTION_BLOCKED,
                        SignalCategory.THROUGHPUT_BLOCKED,
                    )
            }

        val patternLabel =
            when (pattern) {
                PatternRstInjection -> "TCP RST injection"
                PatternThresholdBlocking -> "threshold-based HTTPS blocking"
                PatternSilentDrop -> "silent packet drop"
                PatternMixedDpi -> "mixed DPI blocking"
                else -> "DPI blocking"
            }

        val familyLabel =
            when (family) {
                FamilyFake -> "fake packets with adaptive TTL"
                FamilyDisorder -> "out-of-order delivery (disorder)"
                FamilyTlsrecFake -> "TLS record split + fake"
                else -> family
            }

        return buildString {
            append("Observed probe outcomes match a candidate $patternLabel pattern")
            if (tcpCount > 0) append(" on $tcpCount TCP probe(s)")
            if (serviceCount > 0) append(" with $serviceCount failed service probe(s)")
            append("; the mechanism and cause are not established. Recommend switching to $familyLabel.")
        }
    }

    private data class BlockingSignal(
        val probeType: String,
        val target: String,
        val outcome: String,
        val category: SignalCategory,
    )

    private enum class SignalCategory(
        val label: String,
    ) {
        TCP_RST("TCP RST observations"),
        THRESHOLD_BLOCK("16KB threshold failure observations"),
        SILENT_DROP("Timeout or drop observations"),
        TLS_INTERFERENCE("TLS handshake failure observations"),
        DOMAIN_BLOCKED("Domain failure observations"),
        SERVICE_BLOCKED("Service failure observations"),
        CIRCUMVENTION_BLOCKED("Circumvention tool failure observations"),
        THROUGHPUT_BLOCKED("Throughput failure observations"),
        QUIC_BLOCKED("QUIC failure observations"),
    }

    private fun ScanReport.hasActionableStrategyEvidence(signals: List<BlockingSignal>): Boolean =
        when {
            hasFatHeaderOnlySyntheticBlocking() -> {
                false
            }

            pathMode != ScanPathMode.RAW_PATH -> {
                true
            }

            else -> {
                signals.any { it.isRealReachabilityFailure() } &&
                    signals.sumOf { it.recommendationStrength() } >= MinRawPathRecommendationStrength
            }
        }

    private fun BlockingSignal.isRealReachabilityFailure(): Boolean =
        category in
            setOf(
                SignalCategory.DOMAIN_BLOCKED,
                SignalCategory.SERVICE_BLOCKED,
                SignalCategory.CIRCUMVENTION_BLOCKED,
                SignalCategory.THROUGHPUT_BLOCKED,
                SignalCategory.QUIC_BLOCKED,
            )

    private fun BlockingSignal.recommendationStrength(): Int =
        if (isRealReachabilityFailure()) {
            RealReachabilitySignalWeight
        } else {
            SyntheticTcpSignalWeight
        }
}
