package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent

private const val MillisPerSecond = 1_000L
private const val SecondsPerMinute = 60L
private const val MinutesPerHour = 60L
private const val DefaultAutomaticHandoverProbeDelaySeconds = 15L
private const val DefaultAutomaticHandoverProbeCooldownHours = 24L
private const val DefaultAutomaticStrategyFailureProbeCooldownHours = 4L
private const val DefaultStageRetryDelayMs = 2_000L
private const val DefaultStageRetryBudget = 1
private const val DefaultQuickScanMaxCandidates = 5
private const val DefaultAutomaticStrategyMaxCandidates = 5
private const val DefaultAutomaticAuditDomainTargets = 3
private const val DefaultAutomaticAuditQuicTargets = 2
private const val DefaultManualStrategyDomainTargets = 12
private const val DefaultManualStrategyQuicTargets = 8

data class ActiveProbeSafetyPolicy(
    val automaticHandoverProbeDelayMs: Long = DefaultAutomaticHandoverProbeDelaySeconds * MillisPerSecond,
    val automaticHandoverProbeCooldownMs: Long =
        DefaultAutomaticHandoverProbeCooldownHours * MinutesPerHour * SecondsPerMinute * MillisPerSecond,
    val automaticStrategyFailureProbeCooldownMs: Long =
        DefaultAutomaticStrategyFailureProbeCooldownHours * MinutesPerHour * SecondsPerMinute * MillisPerSecond,
    val stageRetryDelayMs: Long = DefaultStageRetryDelayMs,
    val stageRetryBudget: Int = DefaultStageRetryBudget,
    val quickScanMaxCandidates: Int = DefaultQuickScanMaxCandidates,
    val automaticStrategyMaxCandidates: Int = DefaultAutomaticStrategyMaxCandidates,
    val automaticAuditDomainTargets: Int = DefaultAutomaticAuditDomainTargets,
    val automaticAuditQuicTargets: Int = DefaultAutomaticAuditQuicTargets,
    val manualStrategyDomainTargets: Int = DefaultManualStrategyDomainTargets,
    val manualStrategyQuicTargets: Int = DefaultManualStrategyQuicTargets,
) {
    init {
        require(automaticHandoverProbeDelayMs >= 0L)
        require(automaticHandoverProbeCooldownMs >= 0L)
        require(automaticStrategyFailureProbeCooldownMs >= 0L)
        require(stageRetryDelayMs >= 0L)
        require(stageRetryBudget >= 0)
        require(quickScanMaxCandidates > 0)
        require(automaticStrategyMaxCandidates > 0)
        require(automaticAuditDomainTargets > 0)
        require(automaticAuditQuicTargets > 0)
        require(manualStrategyDomainTargets >= automaticAuditDomainTargets)
        require(manualStrategyQuicTargets >= automaticAuditQuicTargets)
    }

    internal fun cooldownMsForHandoverClassification(classification: String): Long =
        if (classification == AutomaticProbeCoordinator.CLASSIFICATION_STRATEGY_FAILURE) {
            automaticStrategyFailureProbeCooldownMs
        } else {
            automaticHandoverProbeCooldownMs
        }

    internal fun enforceTargetBudget(
        intent: DiagnosticsIntent,
        scanOrigin: DiagnosticsScanOrigin,
        isManual: Boolean,
    ): DiagnosticsIntent {
        if (intent.strategyProbe == null) return intent
        val limits = targetLimits(scanOrigin, isManual)
        val domainTargets = intent.domainTargets.distinctBy(DomainTarget::host).take(limits.domainTargets)
        val quicTargets = intent.quicTargets.distinctBy(QuicTarget::host).take(limits.quicTargets)
        return intent.copy(
            domainTargets = domainTargets,
            quicTargets = quicTargets,
            strategyProbe =
                intent.strategyProbe.copy(
                    targetSelection =
                        intent.strategyProbe.targetSelection?.copy(
                            domainHosts = domainTargets.map(DomainTarget::host),
                            quicHosts = quicTargets.map(QuicTarget::host),
                        ),
                ),
        )
    }

    internal fun enforceMaxCandidates(
        scanOrigin: DiagnosticsScanOrigin,
        requestedMaxCandidates: Int?,
    ): Int? {
        val budget =
            when {
                scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND -> automaticStrategyMaxCandidates
                scanOrigin == DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE -> automaticStrategyMaxCandidates
                else -> null
            }
        return when {
            budget == null -> requestedMaxCandidates
            requestedMaxCandidates == null -> budget
            else -> requestedMaxCandidates.coerceAtMost(budget)
        }
    }

    internal fun validAutomaticAuditCohorts(intent: DiagnosticsIntent) =
        intent.strategyProbeTargetCohorts.filter { cohort ->
            cohort.domainTargets.size == automaticAuditDomainTargets &&
                cohort.quicTargets.size == automaticAuditQuicTargets
        }

    private fun targetLimits(
        scanOrigin: DiagnosticsScanOrigin,
        isManual: Boolean,
    ): ActiveProbeTargetLimits =
        when {
            scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND -> {
                ActiveProbeTargetLimits(automaticAuditDomainTargets, automaticAuditQuicTargets)
            }

            scanOrigin == DiagnosticsScanOrigin.DNS_CORRECTED_REPROBE -> {
                ActiveProbeTargetLimits(automaticAuditDomainTargets, automaticAuditQuicTargets)
            }

            isManual -> {
                ActiveProbeTargetLimits(manualStrategyDomainTargets, manualStrategyQuicTargets)
            }

            else -> {
                ActiveProbeTargetLimits(automaticAuditDomainTargets, automaticAuditQuicTargets)
            }
        }

    private data class ActiveProbeTargetLimits(
        val domainTargets: Int,
        val quicTargets: Int,
    )
}
