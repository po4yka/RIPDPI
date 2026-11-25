package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.finalization.NetworkScopeUnverifiedDiagnosis
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

const val SelectiveMatrixPairMaxStartGapMs: Long = 30L * 60L * 1_000L

@Serializable
data class SelectiveMatrixRevisions(
    val targetSet: String,
    val probeParameters: String,
    val appConfiguration: String,
)

internal fun selectiveMatrixRevisions(
    matrix: SelectiveMatrixConfig,
    pathMode: ScanPathMode,
    settings: AppSettings,
): SelectiveMatrixRevisions =
    SelectiveMatrixRevisions(
        targetSet = sha256Hex(Json.encodeToString(ListSerializer(SelectiveMatrixTarget.serializer()), matrix.targets)),
        probeParameters =
            sha256Hex(
                listOf(matrix.version, matrix.repetitions, matrix.timeoutMs, matrix.maxResponseBytes, pathMode.name)
                    .joinToString("|"),
            ),
        appConfiguration = deriveBypassStrategySignature(settings, routeGroup = null).stableId(),
    )

data class SelectiveMatrixLeg(
    val transport: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val revisions: SelectiveMatrixRevisions?,
    val results: List<ProbeResult>,
)

fun DiagnosticScanSession.toSelectiveMatrixLeg(results: List<ProbeResult>): SelectiveMatrixLeg =
    SelectiveMatrixLeg(networkTransport, startedAt, finishedAt, matrixRevisions, results)

enum class SelectiveMatrixPairMismatch {
    REVISIONS_MISSING,
    TARGET_SET,
    PROBE_PARAMETERS,
    APP_CONFIGURATION,
    TRANSPORT_UNKNOWN,
    SAME_TRANSPORT,
    AGE_LIMIT,
}

enum class SelectiveMatrixTargetStage { AVAILABLE, FAILED, UNMEASURED }

@Serializable
data class SelectiveMatrixTargetLegOutcome(
    val stage: SelectiveMatrixTargetStage,
    val failureStage: String?,
    val observedAttempts: Int,
    val availableAttempts: Int,
)

enum class SelectiveMatrixPairVerdict {
    PATH_SPECIFIC,
    AVAILABLE_ON_BOTH,
    CONTROL,
    INCONCLUSIVE_LEG_INVALID,
    INCONCLUSIVE_CONTROL_FAILED,
    INCONCLUSIVE_FAILED_ON_BOTH,
    INCONCLUSIVE_UNMEASURED,
}

@Serializable
data class SelectiveMatrixPairRow(
    val targetId: String,
    val verdict: SelectiveMatrixPairVerdict,
    val first: SelectiveMatrixTargetLegOutcome,
    val second: SelectiveMatrixTargetLegOutcome,
)

@Serializable
data class SelectiveMatrixLegSummary(
    val transport: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val valid: Boolean,
    val controlsValid: Boolean,
)

sealed interface SelectiveMatrixComparison {
    data class Unavailable(
        val mismatches: List<SelectiveMatrixPairMismatch>,
    ) : SelectiveMatrixComparison

    data class Compared(
        val first: SelectiveMatrixLegSummary,
        val second: SelectiveMatrixLegSummary,
        val rows: List<SelectiveMatrixPairRow>,
    ) : SelectiveMatrixComparison {
        val pathSpecificCount: Int get() = rows.count { it.verdict == SelectiveMatrixPairVerdict.PATH_SPECIFIC }

        val comparableCount: Int
            get() = pathSpecificCount + rows.count { it.verdict == SelectiveMatrixPairVerdict.AVAILABLE_ON_BOTH }
    }
}

fun compareSelectiveMatrixSessions(
    a: SelectiveMatrixLeg,
    b: SelectiveMatrixLeg,
): SelectiveMatrixComparison {
    val (first, second) = if (a.startedAt <= b.startedAt) a to b else b to a
    val mismatches = pairMismatches(first, second)
    if (mismatches.isNotEmpty()) return SelectiveMatrixComparison.Unavailable(mismatches)
    val firstTargets = first.targetOutcomes()
    val secondTargets = second.targetOutcomes()
    val firstSummary = first.summarize(firstTargets)
    val secondSummary = second.summarize(secondTargets)
    val rows =
        firstTargets.map { (targetId, firstTarget) ->
            val secondTarget = secondTargets.firstOrNull { it.first == targetId }?.second ?: Unmeasured
            SelectiveMatrixPairRow(
                targetId = targetId,
                verdict = verdict(firstTarget, secondTarget, firstSummary, secondSummary),
                first = firstTarget.outcome,
                second = secondTarget.outcome,
            )
        }
    return SelectiveMatrixComparison.Compared(firstSummary, secondSummary, rows)
}

private fun pairMismatches(
    first: SelectiveMatrixLeg,
    second: SelectiveMatrixLeg,
): List<SelectiveMatrixPairMismatch> =
    buildList {
        val left = first.revisions
        val right = second.revisions
        if (left == null || right == null) {
            add(SelectiveMatrixPairMismatch.REVISIONS_MISSING)
        } else {
            if (left.targetSet != right.targetSet) add(SelectiveMatrixPairMismatch.TARGET_SET)
            if (left.probeParameters != right.probeParameters) add(SelectiveMatrixPairMismatch.PROBE_PARAMETERS)
            if (left.appConfiguration != right.appConfiguration) add(SelectiveMatrixPairMismatch.APP_CONFIGURATION)
        }
        when {
            first.transport.isNullOrBlank() || second.transport.isNullOrBlank() -> {
                add(SelectiveMatrixPairMismatch.TRANSPORT_UNKNOWN)
            }

            first.transport == second.transport -> {
                add(SelectiveMatrixPairMismatch.SAME_TRANSPORT)
            }
        }
        if (second.startedAt - first.startedAt > SelectiveMatrixPairMaxStartGapMs) {
            add(SelectiveMatrixPairMismatch.AGE_LIMIT)
        }
    }

private class TargetOnLeg(
    val cohort: String?,
    val outcome: SelectiveMatrixTargetLegOutcome,
)

private val Unmeasured =
    TargetOnLeg(null, SelectiveMatrixTargetLegOutcome(SelectiveMatrixTargetStage.UNMEASURED, null, 0, 0))

private fun SelectiveMatrixLeg.summaryDetails(): Map<String, String> =
    results
        .firstOrNull { it.probeType == MatrixSummaryProbeType }
        ?.details
        ?.associate { it.key to it.value }
        .orEmpty()

private fun SelectiveMatrixLeg.targetOutcomes(): List<Pair<String, TargetOnLeg>> {
    val repetitions = summaryDetails()["repetitionsPerTarget"]?.toIntOrNull()
    return results
        .filter { it.probeType == MatrixAttemptProbeType && it.outcome !in MatrixUnobservedOutcomes }
        .mapNotNull { result ->
            result.details
                .firstOrNull { it.key == "targetId" }
                ?.value
                ?.let { it to result }
        }.groupBy({ it.first }, { it.second })
        .map { (targetId, attempts) ->
            val details = attempts.map { attempt -> attempt.details.associate { it.key to it.value } }
            val failureStages = details.map { it["failureStage"] }.distinct()
            val complete = repetitions != null && attempts.size == repetitions
            val stage =
                when {
                    complete && attempts.all { it.outcome == "matrix_target_available" } -> {
                        SelectiveMatrixTargetStage.AVAILABLE
                    }

                    complete && attempts.all { it.outcome == "matrix_target_transport_failed" } &&
                        failureStages.size == 1 -> {
                        SelectiveMatrixTargetStage.FAILED
                    }

                    else -> {
                        SelectiveMatrixTargetStage.UNMEASURED
                    }
                }
            targetId to
                TargetOnLeg(
                    cohort = details.firstNotNullOfOrNull { it["cohort"] },
                    outcome =
                        SelectiveMatrixTargetLegOutcome(
                            stage = stage,
                            failureStage = failureStages.singleOrNull(),
                            observedAttempts = attempts.size,
                            availableAttempts = attempts.count { it.outcome == "matrix_target_available" },
                        ),
                )
        }
}

private fun SelectiveMatrixLeg.summarize(targets: List<Pair<String, TargetOnLeg>>): SelectiveMatrixLegSummary {
    val controls = targets.map { it.second }.filter { it.cohort == MatrixControlCohort }
    return SelectiveMatrixLegSummary(
        transport = requireNotNull(transport),
        startedAt = startedAt,
        finishedAt = finishedAt,
        valid = summaryDetails()["reason"] != NetworkScopeUnverifiedDiagnosis,
        controlsValid =
            controls.isNotEmpty() && controls.all { it.outcome.stage == SelectiveMatrixTargetStage.AVAILABLE },
    )
}

private fun verdict(
    first: TargetOnLeg,
    second: TargetOnLeg,
    firstLeg: SelectiveMatrixLegSummary,
    secondLeg: SelectiveMatrixLegSummary,
): SelectiveMatrixPairVerdict {
    val stages = setOf(first.outcome.stage, second.outcome.stage)
    return when {
        MatrixControlCohort in setOf(first.cohort, second.cohort) -> {
            SelectiveMatrixPairVerdict.CONTROL
        }

        !firstLeg.valid || !secondLeg.valid -> {
            SelectiveMatrixPairVerdict.INCONCLUSIVE_LEG_INVALID
        }

        !firstLeg.controlsValid || !secondLeg.controlsValid -> {
            SelectiveMatrixPairVerdict.INCONCLUSIVE_CONTROL_FAILED
        }

        stages == setOf(SelectiveMatrixTargetStage.AVAILABLE, SelectiveMatrixTargetStage.FAILED) -> {
            SelectiveMatrixPairVerdict.PATH_SPECIFIC
        }

        stages == setOf(SelectiveMatrixTargetStage.AVAILABLE) -> {
            SelectiveMatrixPairVerdict.AVAILABLE_ON_BOTH
        }

        stages == setOf(SelectiveMatrixTargetStage.FAILED) -> {
            SelectiveMatrixPairVerdict.INCONCLUSIVE_FAILED_ON_BOTH
        }

        else -> {
            SelectiveMatrixPairVerdict.INCONCLUSIVE_UNMEASURED
        }
    }
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private const val MatrixAttemptProbeType = "selective_availability"
private const val MatrixSummaryProbeType = "selective_availability_summary"
private const val MatrixControlCohort = "declared_available"
private val MatrixUnobservedOutcomes = setOf("matrix_target_invalid", "matrix_target_cancelled")
