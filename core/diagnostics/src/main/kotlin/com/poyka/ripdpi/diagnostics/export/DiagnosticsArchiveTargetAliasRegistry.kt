package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire

internal class DiagnosticsArchiveTargetAliasRegistry private constructor(
    private val aliasesByTarget: Map<String, String>,
    val payload: DiagnosticsArchiveTargetAliasesPayload,
) {
    fun aliasFor(target: String?): String? = target?.canonicalArchiveTarget()?.let(aliasesByTarget::get)

    companion object {
        fun build(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveTargetAliasRegistry {
            val occurrences = linkedMapOf<String, MutableSet<String>>()
            collectScope(
                report = selection.primaryReport,
                results = selection.primaryResults,
                prefix = "",
                occurrences = occurrences,
            )
            selection.compositeStages.forEach { stage ->
                collectScope(
                    report = stage.report,
                    results = stage.results,
                    prefix = "stages/${stage.stageSummary.stageKey}/",
                    occurrences = occurrences,
                )
            }
            val aliasesByTarget =
                occurrences.keys
                    .sorted()
                    .mapIndexed { index, target -> target to "target-${index + 1}" }
                    .toMap()
            val payload =
                DiagnosticsArchiveTargetAliasesPayload(
                    aliases =
                        aliasesByTarget.map { (target, alias) ->
                            val evidenceRefs = occurrences.getValue(target).sorted()
                            DiagnosticsArchiveTargetAliasEntry(
                                alias = alias,
                                occurrenceCount = evidenceRefs.size,
                                evidenceRefs = evidenceRefs,
                            )
                        },
                )
            return DiagnosticsArchiveTargetAliasRegistry(aliasesByTarget, payload)
        }

        private fun collectScope(
            report: EngineScanReportWire?,
            results: List<com.poyka.ripdpi.data.diagnostics.ProbeResultEntity>,
            prefix: String,
            occurrences: MutableMap<String, MutableSet<String>>,
        ) {
            report?.observations.orEmpty().forEachIndexed { index, observation ->
                occurrences.record(observation.target, "${prefix}report.json#/observations/$index")
            }
            report?.strategyProbeReport?.attempts.orEmpty().forEachIndexed { index, attempt ->
                occurrences.record(attempt.target, "${prefix}attempts.jsonl#L${index + 1}")
            }
            results.forEachIndexed { index, result ->
                occurrences.record(result.target, "${prefix}probe-results.csv#L${index + 2}")
            }
        }
    }
}

private fun MutableMap<String, MutableSet<String>>.record(
    target: String?,
    evidenceRef: String,
) {
    val key = target?.canonicalArchiveTarget()?.takeIf(String::isNotEmpty) ?: return
    getOrPut(key, ::linkedSetOf).add(evidenceRef)
}

private fun String.canonicalArchiveTarget(): String = trim().lowercase().removeSuffix(".")
