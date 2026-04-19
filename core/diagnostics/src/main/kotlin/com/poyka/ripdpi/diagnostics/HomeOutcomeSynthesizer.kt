package com.poyka.ripdpi.diagnostics

private const val MaxNextSteps = 4
private const val DetectionFindingPreviewLimit = 2
private const val FailedStageSpotlightLimit = 2

/**
 * Pure derivation of a plain-language headline + 2-4 next-step recommendations
 * from the existing outcome data. No IO, no new probes.
 */
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
internal fun synthesizeActionableSummary(outcome: DiagnosticsHomeCompositeOutcome): Pair<String?, List<String>> {
    val steps = mutableListOf<String>()
    val headlineParts = mutableListOf<String>()

    when (outcome.detectionVerdict) {
        DiagnosticsHomeDetectionVerdict.DETECTED -> {
            headlineParts += "VPN is detectable on this network"
            outcome.detectionFindings.take(DetectionFindingPreviewLimit).forEach { finding ->
                steps += "Detection signal: $finding"
            }
        }

        DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW -> {
            headlineParts += "Some detection checks need review (likely missing permissions)"
        }

        DiagnosticsHomeDetectionVerdict.NOT_DETECTED -> {
            Unit
        }

        null -> {
            Unit
        }
    }

    val installedDetectorCount = outcome.installedVpnDetectorCount ?: 0
    if (installedDetectorCount > 0) {
        headlineParts +=
            "$installedDetectorCount VPN-detector app${if (installedDetectorCount == 1) "" else "s"} installed"
        val tops = outcome.installedVpnDetectorTopApps.take(2).joinToString(", ")
        if (tops.isNotBlank()) {
            steps += "Add per-app routing for: $tops"
        }
    }

    if (outcome.actionable && outcome.appliedSettings.isNotEmpty()) {
        headlineParts +=
            "Applied ${outcome.appliedSettings.size} " +
            "bypass setting${if (outcome.appliedSettings.size == 1) "" else "s"}"
    }

    if (outcome.failedStageCount > 0) {
        val failedStages =
            outcome.stageSummaries
                .filter { it.status == DiagnosticsHomeCompositeStageStatus.FAILED }
                .take(FailedStageSpotlightLimit)
        if (failedStages.isNotEmpty()) {
            steps += "Failed: " + failedStages.joinToString(", ") { it.stageLabel }
        }
    }

    when (outcome.bufferbloat?.grade) {
        HomeBufferbloatGrade.D, HomeBufferbloatGrade.F -> {
            steps += "High bufferbloat — consider QoS or a different relay transport"
        }

        else -> {
            Unit
        }
    }

    when (outcome.dnsCharacterization?.resolverClass) {
        HomeDnsResolverClass.POSSIBLE_POISONING -> {
            steps += "DNS poisoning suspected — switch to encrypted DNS"
        }

        HomeDnsResolverClass.POSSIBLE_TRANSPARENT_PROXY -> {
            steps += "Transparent DNS proxy detected — switch to encrypted DNS"
        }

        HomeDnsResolverClass.DOH_UNREACHABLE -> {
            steps += "Encrypted DNS endpoints unreachable — DNS may be hijacked"
        }

        else -> {
            Unit
        }
    }

    outcome.regressionDelta?.newlyFailedStageKeys?.takeIf { it.isNotEmpty() }?.let { newlyFailed ->
        steps += "Regression: ${newlyFailed.joinToString(", ")} stopped working since the previous run"
    }

    val ledgerWinner = outcome.strategyEffectiveness.maxByOrNull { it.successCount - it.failureCount }
    if (ledgerWinner != null && ledgerWinner.successCount > 0) {
        steps += "Best historical edge on this network: ${ledgerWinner.label} " +
            "(${ledgerWinner.successCount} pass / ${ledgerWinner.failureCount} fail)"
    }

    val headline =
        when {
            headlineParts.isNotEmpty() -> headlineParts.joinToString(". ") + "."
            outcome.actionable -> "Analysis complete and bypass applied."
            outcome.completedStageCount == 0 -> null
            else -> "Analysis complete — no actionable bypass identified."
        }
    return headline to steps.distinct().take(MaxNextSteps)
}

@Suppress("detekt.ReturnCount")
internal fun computeRegressionDelta(
    current: DiagnosticsHomeCompositeOutcome,
    previous: DiagnosticsHomeCompositeOutcome?,
): HomeRegressionDelta? {
    if (previous == null) return null
    if (previous.runId == current.runId) return null
    val previousByKey = previous.stageSummaries.associateBy { it.stageKey }
    val currentByKey = current.stageSummaries.associateBy { it.stageKey }
    val newlyFailed = mutableListOf<String>()
    val newlyRecovered = mutableListOf<String>()
    var unchanged = 0
    currentByKey.forEach { (key, stage) ->
        val prior = previousByKey[key] ?: return@forEach
        val priorOk = prior.status == DiagnosticsHomeCompositeStageStatus.COMPLETED
        val currentOk = stage.status == DiagnosticsHomeCompositeStageStatus.COMPLETED
        when {
            priorOk && !currentOk -> newlyFailed += key
            !priorOk && currentOk -> newlyRecovered += key
            else -> unchanged++
        }
    }
    if (newlyFailed.isEmpty() && newlyRecovered.isEmpty()) return null
    return HomeRegressionDelta(
        previousRunId = previous.runId,
        newlyFailedStageKeys = newlyFailed,
        newlyRecoveredStageKeys = newlyRecovered,
        unchangedStageCount = unchanged,
    )
}
