package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore

private const val EffectivenessLedgerLimit = 5
private const val EffectivenessIpDisplayLength = 24

internal suspend fun loadStrategyEffectiveness(
    networkEdgePreferenceStore: NetworkEdgePreferenceStore,
    fingerprintHash: String,
): List<HomeStrategyEffectivenessEntry> {
    val byHost = networkEdgePreferenceStore.getPreferredEdgesForRuntime(fingerprintHash)
    if (byHost.isEmpty()) return emptyList()
    return byHost
        .flatMap { (host, edges) ->
            edges.map { candidate ->
                val ipPreview = candidate.ip.take(EffectivenessIpDisplayLength)
                val transportSuffix =
                    candidate.transportKind
                        .takeIf { it.isNotBlank() }
                        ?.let { " ($it)" }
                        .orEmpty()
                HomeStrategyEffectivenessEntry(
                    label = "$host → $ipPreview$transportSuffix",
                    successCount = candidate.successCount,
                    failureCount = candidate.failureCount,
                )
            }
        }.sortedWith(
            compareByDescending<HomeStrategyEffectivenessEntry> { it.successCount - it.failureCount }
                .thenByDescending { it.successCount },
        ).take(EffectivenessLedgerLimit)
}
