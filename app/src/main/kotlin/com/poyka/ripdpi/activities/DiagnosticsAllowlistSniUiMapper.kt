package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpi.AllowlistSniResult
import com.poyka.ripdpi.diagnostics.dpi.CompatibleSni
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.toPersistentList

internal fun List<AllowlistSniResult>.toAllowlistSniUiModel(
    strings: StringResolver,
): DiagnosticsAllowlistSniToolUiModel {
    val compatibleCount = sumOf { result -> result.compatibleSnis.size }
    return DiagnosticsAllowlistSniToolUiModel(
        state = DiagnosticsAllowlistSniState.Complete,
        summary = strings.getQuantityString(R.plurals.diagnostics_sni_complete, compatibleCount, compatibleCount, size),
        metrics =
            listOf(
                DiagnosticsMetricUiModel(
                    strings.getString(R.string.diagnostics_sni_metric_asns),
                    size.toString(),
                    DiagnosticsTone.Info,
                ),
                DiagnosticsMetricUiModel(
                    strings.getString(R.string.diagnostics_byoh_metric_compatible),
                    compatibleCount.toString(),
                    compatibleTone(compatibleCount),
                ),
                DiagnosticsMetricUiModel(
                    strings.getString(R.string.diagnostics_sni_metric_tried),
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
