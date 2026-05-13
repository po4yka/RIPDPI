package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityDomainUiModel
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityDoqUiModel
import com.poyka.ripdpi.activities.DiagnosticsDohBootstrapUiModel
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DnsIntegrityDomainRows(rows: List<DiagnosticsDnsIntegrityDomainUiModel>) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (rows.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            rows.forEach { row ->
                StatusIndicator(label = "${row.domain}: ${row.verdict}", tone = statusTone(row.tone))
                androidx.compose.material3.Text(
                    text = "UDP ${row.udpAnswer} · DoH ${row.dohAnswer}",
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
internal fun DnsIntegrityDoqRows(rows: List<DiagnosticsDnsIntegrityDoqUiModel>) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (rows.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            rows.forEach { row ->
                StatusIndicator(
                    label = "DoQ ${row.provider} ${row.domain}: ${row.verdict}",
                    tone = statusTone(row.tone),
                )
                androidx.compose.material3.Text(
                    text = "${row.endpoint} · ${row.resolvedIps} · ${row.detail}",
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
internal fun DnsIntegrityDohBootstrapRows(rows: List<DiagnosticsDohBootstrapUiModel>) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (rows.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            rows.forEach { row ->
                StatusIndicator(
                    label = "DoH bootstrap ${row.provider}: ${row.verdict}",
                    tone = statusTone(row.tone),
                )
                androidx.compose.material3.Text(
                    text = "${row.hostname} · ${row.detail}",
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}
