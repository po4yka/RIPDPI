package com.poyka.ripdpi.ui.screens.xray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.xray.XrayConnectionStage
import com.poyka.ripdpi.data.xray.XrayProviderProbeKind
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderProbeResult
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Bundled, immutable view state for the Diagnostics Xray provider card. Carries
 * the live snapshot, the latest user-triggered probe report (when run), and the
 * probe-running flag. When [probeReport] is present it supersedes [snapshot] for
 * display; otherwise the card renders the passive snapshot with no probes.
 */
data class XrayProviderToolUiModel(
    val snapshot: XrayProviderSnapshot? = null,
    val probeReport: XrayProviderProbeReport? = null,
    val probeRunning: Boolean = false,
) {
    /** True when there is an active provider to render (live snapshot present). */
    val isVisible: Boolean
        get() = snapshot != null || probeReport != null

    /** Report to render: the probe report if run, else a passive snapshot-only report. */
    val report: XrayProviderProbeReport?
        get() = probeReport ?: snapshot?.let { XrayProviderProbeReport(snapshot = it) }
}

/**
 * Diagnostics / Settings card rendering the embedded Xray provider health axis
 * DISTINCTLY from the tunnel data plane (decision 6). It shows the typed
 * [XrayConnectionStage] + [com.poyka.ripdpi.data.xray.XrayProviderFailureClass]
 * with a provider-specific tone, the engine version / listener / outbound
 * states, the last config findings, and the user-triggered provider-path probe
 * results — never reusing the destructive tunnel error treatment.
 *
 * The card is self-contained over an [XrayProviderProbeReport] so it renders the
 * five `XrayProviderDiagnosticsFixtures` deterministically in Roborazzi without
 * any live engine. All text is already privacy-safe.
 */
@Composable
internal fun XrayProviderStatusCard(
    report: XrayProviderProbeReport,
    onRunProbe: () -> Unit,
    probeRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val snapshot = report.snapshot
    val stage = XrayConnectionStage.fromSnapshot(snapshot)
    val stageTone = stage.tone()
    RipDpiCard(
        variant = RipDpiCardVariant.Outlined,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.XrayProviderStatusCard),
    ) {
        StatusIndicator(
            label = stage.title(),
            tone = stageTone.statusTone(),
            pulsing = stageTone == XrayProviderTone.Progress,
        )
        Text(
            text = stringResource(R.string.xray_provider_card_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stage.subtitle(snapshot),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (stage is XrayConnectionStage.ProviderFailed) colors.warning else colors.mutedForeground,
        )
        XrayProviderEngineRows(snapshot = snapshot)
        XrayProviderConfigFindingRows(snapshot = snapshot)
        XrayProviderProbeRows(report = report)
        RipDpiButton(
            text =
                if (probeRunning) {
                    stringResource(R.string.xray_provider_probe_running)
                } else {
                    stringResource(R.string.xray_provider_probe_run)
                },
            enabled = !probeRunning,
            onClick = onRunProbe,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.XrayProviderProbeRun),
        )
    }
}

@Composable
private fun XrayProviderEngineRows(snapshot: XrayProviderSnapshot) {
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        snapshot.xrayVersion?.let { version ->
            Text(
                text = stringResource(R.string.xray_provider_engine_version_format, version),
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
        snapshot.profileName?.let { name ->
            Text(
                text = stringResource(R.string.xray_provider_profile_format, name),
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
        Text(
            text =
                stringResource(
                    R.string.xray_provider_engine_states_format,
                    snapshot.listenerState.name,
                    snapshot.outboundHealth.name,
                ),
            style = RipDpiThemeTokens.type.monoSmall,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun XrayProviderConfigFindingRows(snapshot: XrayProviderSnapshot) {
    if (snapshot.configFindings.isEmpty()) return
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        snapshot.configFindings.forEach { finding ->
            StatusIndicator(
                label = finding.code.name,
                tone = com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone.Error,
            )
            Text(
                text = finding.message,
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun XrayProviderProbeRows(report: XrayProviderProbeReport) {
    if (report.probes.isEmpty()) return
    val colors = RipDpiThemeTokens.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        report.probes.forEach { probe ->
            StatusIndicator(
                label = probe.kind.label(),
                tone = probe.statusTone(),
            )
            val detail =
                buildString {
                    append(probe.statusLabel())
                    probe.latencyMs?.let { append(" · ${it}ms") }
                    probe.detailRedacted?.let { append(" · $it") }
                }
            Text(
                text = detail,
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun com.poyka.ripdpi.data.xray.XrayProviderProbeResult.statusLabel(): String =
    when {
        kind == com.poyka.ripdpi.data.xray.XrayProviderProbeKind.StatApi -> {
            stringResource(R.string.xray_provider_probe_not_applicable)
        }

        ok -> {
            stringResource(R.string.xray_provider_probe_ok)
        }

        else -> {
            stringResource(R.string.xray_provider_probe_failed_label)
        }
    }

private fun XrayProviderProbeResult.statusTone(): StatusIndicatorTone =
    when {
        kind == XrayProviderProbeKind.StatApi -> {
            StatusIndicatorTone.Idle
        }

        ok -> {
            StatusIndicatorTone.Active
        }

        else -> {
            StatusIndicatorTone.Error
        }
    }
