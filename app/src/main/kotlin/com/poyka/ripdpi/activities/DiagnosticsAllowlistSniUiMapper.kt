package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpi.AllowlistSniResult
import com.poyka.ripdpi.diagnostics.dpi.CompatibleSni
import kotlinx.collections.immutable.toPersistentList

internal fun List<AllowlistSniResult>.toAllowlistSniUiModel(): DiagnosticsAllowlistSniToolUiModel {
    val compatibleCount = sumOf { result -> result.compatibleSnis.size }
    return DiagnosticsAllowlistSniToolUiModel(
        state = DiagnosticsAllowlistSniState.Complete,
        summary = "$compatibleCount compatible SNI entries found across $size ASNs.",
        metrics =
            listOf(
                DiagnosticsMetricUiModel("ASNs", size.toString(), DiagnosticsTone.Info),
                DiagnosticsMetricUiModel("compatible", compatibleCount.toString(), compatibleTone(compatibleCount)),
                DiagnosticsMetricUiModel(
                    "tried",
                    sumOf { result ->
                        result.triedCount
                    }.toString(),
                    DiagnosticsTone.Neutral,
                ),
            ).toPersistentList(),
        rows =
            map { result -> result.toUiModel() }
                .toPersistentList(),
        enabled = true,
    )
}

private fun AllowlistSniResult.toUiModel(): DiagnosticsAllowlistSniAsnUiModel =
    DiagnosticsAllowlistSniAsnUiModel(
        asn = asn,
        provider = provider,
        ip = ip,
        triedCount = triedCount.toString(),
        compatibleSnis =
            compatibleSnis
                .map { sni -> sni.toUiModel() }
                .toPersistentList(),
        tone = compatibleTone(compatibleSnis.size),
    )

private fun CompatibleSni.toUiModel(): DiagnosticsCompatibleSniUiModel =
    DiagnosticsCompatibleSniUiModel(
        label = "$sni [$lineNumber]",
        value = sni,
    )

private fun compatibleTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Warning
    } else {
        DiagnosticsTone.Positive
    }
