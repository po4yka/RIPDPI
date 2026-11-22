package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectivityStageOutcome {
    SUCCEEDED,
    FAILED,
    UNMEASURED,
    CONFLICTING,
}

@Serializable
enum class ConnectivityActivePathCoverage {
    NOT_RUN,
    INCOMPLETE,
    COMPLETE,
}

@Serializable
data class ConnectivityStageMeasurement(
    val outcome: ConnectivityStageOutcome,
    val samples: Int,
) {
    val measured: Boolean
        get() = outcome == ConnectivityStageOutcome.SUCCEEDED || outcome == ConnectivityStageOutcome.FAILED
}

@Serializable
data class ConnectivityComparedStage(
    val target: String,
    val stage: String,
    val control: Boolean,
    val raw: ConnectivityStageMeasurement,
    val inPath: ConnectivityStageMeasurement,
) {
    val comparable: Boolean
        get() = raw.measured && inPath.measured
}

@Serializable
data class ConnectivityPathComparison(
    val completeRawReports: Int,
    val incompleteRawReports: Int,
    val activePathCoverage: ConnectivityActivePathCoverage,
    val activePathIsVpn: Boolean = false,
    val stages: List<ConnectivityComparedStage> = emptyList(),
    val unmeasuredTargets: List<String> = emptyList(),
    val httpRecoveredTargets: List<String> = emptyList(),
)

internal data class ConnectivityTargetKey(
    val kind: ObservationKind,
    val id: String,
)

internal data class ConnectivityTargetObservation(
    val sessionId: String,
    val profileId: String,
    val target: TargetOutcome,
)

internal typealias ConnectivityTargetObservations = Map<ConnectivityTargetKey, List<ConnectivityTargetObservation>>

internal data class ConnectivityComparisonInputs(
    val raw: ConnectivityTargetObservations,
    val inPath: ConnectivityTargetObservations,
    val rawProfilesByTarget: Map<ConnectivityTargetKey, Set<String>>?,
)

internal fun ConnectivityComparisonInputs.matchedRaw(): ConnectivityTargetObservations =
    rawProfilesByTarget?.let { profiles ->
        raw
            .mapNotNull { (key, observations) ->
                val allowed = profiles[key].orEmpty()
                val matches = observations.filter { it.profileId in allowed }
                matches.takeIf { it.isNotEmpty() }?.let { key to it }
            }.toMap()
    } ?: raw

internal fun ConnectivityPathComparison.withObservations(
    inputs: ConnectivityComparisonInputs,
): ConnectivityPathComparison {
    val rows = mutableListOf<ConnectivityComparedStage>()
    val unmeasured = mutableListOf<String>()
    val recovered = mutableListOf<String>()
    val targets = inputs.rawProfilesByTarget?.keys ?: (inputs.raw.keys + inputs.inPath.keys)
    val matchedRaw = inputs.matchedRaw()
    for (key in targets) {
        val active = inputs.inPath[key].orEmpty()
        val raw = matchedRaw[key].orEmpty()
        val rawStages = raw.stageMeasurements()
        val activeStages = active.stageMeasurements()
        val stageNames = rawStages.keys + activeStages.keys
        val control = raw.any { it.target.isControl } || active.any { it.target.isControl }
        if (stageNames.isEmpty()) unmeasured += key.id
        for (name in stageNames.sorted()) {
            rows +=
                ConnectivityComparedStage(
                    target = key.id,
                    stage = name,
                    control = control,
                    raw = rawStages[name] ?: UnmeasuredStage,
                    inPath = activeStages[name] ?: UnmeasuredStage,
                )
        }
        val httpRecovered =
            rawStages["http"]?.outcome == ConnectivityStageOutcome.FAILED &&
                activeStages["http"]?.outcome == ConnectivityStageOutcome.SUCCEEDED
        val eligibleHttpTarget = key.kind == ObservationKind.DOMAIN && !control && active.none { it.target.failed }
        if (eligibleHttpTarget && httpRecovered) {
            recovered += key.id
        }
    }
    return copy(stages = rows, unmeasuredTargets = unmeasured.distinct(), httpRecoveredTargets = recovered.distinct())
}

private val UnmeasuredStage = ConnectivityStageMeasurement(ConnectivityStageOutcome.UNMEASURED, 0)

private fun List<ConnectivityTargetObservation>.stageMeasurements(): Map<String, ConnectivityStageMeasurement> =
    flatMap { observation ->
        observation.target.components.map { (name, bucket) -> Triple(name, observation.sessionId, bucket) }
    }.groupBy { it.first }
        .mapValues { (_, samples) ->
            val measured =
                samples.filter { (_, _, bucket) ->
                    bucket == DiagnosticsOutcomeBucket.Healthy || bucket == DiagnosticsOutcomeBucket.Failed
                }
            val outcome =
                when {
                    measured.isEmpty() -> ConnectivityStageOutcome.UNMEASURED
                    measured.map { it.third }.distinct().size > 1 -> ConnectivityStageOutcome.CONFLICTING
                    measured.first().third == DiagnosticsOutcomeBucket.Healthy -> ConnectivityStageOutcome.SUCCEEDED
                    else -> ConnectivityStageOutcome.FAILED
                }
            ConnectivityStageMeasurement(outcome, measured.map { it.second }.distinct().size)
        }

internal fun ConnectivityPathComparison.confidenceFor(code: ConnectivityAssessmentCode): String {
    val conflicting = stages.any { it.raw.outcome.isConflicting() || it.inPath.outcome.isConflicting() }
    val incomplete = incompleteRawReports > 0 || activePathCoverage == ConnectivityActivePathCoverage.INCOMPLETE
    if (code == ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE || conflicting || incomplete) return "low"
    val relevant = stages.filter { it.supports(code) }
    val expectedControlOutcome =
        when (code) {
            ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE,
            ConnectivityAssessmentCode.RAW_NETWORK_INTERNATIONAL_FAILURE,
            -> ConnectivityStageOutcome.FAILED

            else -> ConnectivityStageOutcome.SUCCEEDED
        }
    val repeatableControl =
        stages.any { it.control && it.comparable && it.raw.samples >= 2 && it.raw.outcome == expectedControlOutcome }
    return when {
        code == ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE ||
            code == ConnectivityAssessmentCode.RESOLVER_INTERFERENCE -> "medium"

        relevant
            .filter { it.raw.samples >= 2 }
            .map { it.target }
            .distinct()
            .size >= 2 &&
            repeatableControl -> "high"

        relevant.isNotEmpty() || code == ConnectivityAssessmentCode.VPN_PATH_REGRESSION -> "medium"

        else -> "low"
    }
}

private fun ConnectivityComparedStage.supports(code: ConnectivityAssessmentCode): Boolean =
    comparable && !control &&
        if (code == ConnectivityAssessmentCode.VPN_PATH_REGRESSION) {
            raw.outcome == ConnectivityStageOutcome.SUCCEEDED && inPath.outcome == ConnectivityStageOutcome.FAILED
        } else {
            raw.outcome == ConnectivityStageOutcome.FAILED
        }

private fun ConnectivityStageOutcome.isConflicting(): Boolean = this == ConnectivityStageOutcome.CONFLICTING
