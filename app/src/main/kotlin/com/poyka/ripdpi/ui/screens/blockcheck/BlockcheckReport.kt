package com.poyka.ripdpi.ui.screens.blockcheck

import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.serialization.RipDpiPrettyDefaultsJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val BlockcheckReportJson = RipDpiPrettyDefaultsJson

fun encodeBlockcheckReport(state: BlockcheckUiState): String {
    val payload =
        JsonObject(
            mapOf(
                "state" to JsonPrimitive(state.runState.name.lowercase()),
                "domains" to JsonArray(state.domains.map(::JsonPrimitive)),
                "diagnoses" to JsonArray(state.diagnoses.map(::encodeDiagnosis)),
                "recommended_strategy_id" to JsonPrimitive(state.recommendedStrategyId),
                "results" to JsonArray(state.results.map(::encodeProbeResult)),
                "ranked_strategies" to JsonArray(state.rankedStrategies.map(::encodeRankedStrategy)),
            ),
        )
    return BlockcheckReportJson.encodeToString(JsonObject.serializer(), payload)
}

private fun encodeDiagnosis(diagnosis: BlockcheckSiteDiagnosis): JsonObject =
    JsonObject(
        mapOf(
            "domain" to JsonPrimitive(diagnosis.domain),
            "layer" to
                JsonPrimitive(
                    diagnosis.diagnosis.layer.name
                        .lowercase(),
                ),
            "bypass_class" to
                JsonPrimitive(
                    diagnosis.diagnosis.bypassClass.name
                        .lowercase(),
                ),
            "confidence" to
                JsonPrimitive(
                    diagnosis.diagnosis.confidence.name
                        .lowercase(),
                ),
            "reason_code" to JsonPrimitive(diagnosis.diagnosis.reasonCode),
            "probe_verdict" to JsonPrimitive(diagnosis.probeVerdict),
        ),
    )

private fun encodeProbeResult(result: StrategyProbeResult): JsonObject =
    JsonObject(
        mapOf(
            "strategy_id" to JsonPrimitive(result.strategyId),
            "strategy_label" to JsonPrimitive(result.strategyLabel),
            "domain" to JsonPrimitive(result.domain),
            "success" to JsonPrimitive(result.success),
            "latency_ms" to JsonPrimitive(result.latencyMs),
            "dns_tampered" to JsonPrimitive(result.dnsTampered),
            "failure_kind" to JsonPrimitive(result.failureKind?.name),
            "error" to JsonPrimitive(result.error),
        ),
    )

private fun encodeRankedStrategy(result: RankedStrategyProbeResult): JsonObject =
    JsonObject(
        mapOf(
            "strategy_id" to JsonPrimitive(result.strategyId),
            "strategy_label" to JsonPrimitive(result.strategyLabel),
            "total" to JsonPrimitive(result.total),
            "successes" to JsonPrimitive(result.successes),
            "success_rate" to JsonPrimitive(result.successRate),
            "average_latency_ms" to JsonPrimitive(result.averageLatencyMs),
            "dns_tampered_count" to JsonPrimitive(result.dnsTamperedCount),
        ),
    )
