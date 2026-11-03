package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.deriveBypassStrategySignature
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsFieldUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsMetricUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsUiFactorySupport
import java.util.Locale

internal fun StringResolver.signatureRouteGroupField(signature: BypassStrategySignature): DiagnosticsFieldUiModel =
    DiagnosticsFieldUiModel(
        getString(R.string.diagnostics_field_route_group),
        signature.routeGroup ?: getString(R.string.diagnostics_local_unknown),
    )

internal fun DiagnosticsUiFactorySupport.pathShiftMetric(detail: BypassApproachDetail): DiagnosticsMetricUiModel =
    DiagnosticsMetricUiModel(
        context.getString(R.string.diagnostics_metric_route_changes),
        detail.summary.recentRuntimeHealth.routeChanges
            .toString(),
        DiagnosticsTone.Info,
    )

private fun parseServiceModeOrDefault(serviceMode: String?): Mode =
    when (serviceMode?.uppercase(Locale.US)) {
        "PROXY" -> Mode.Proxy
        "VPN" -> Mode.VPN
        else -> Mode.VPN
    }

internal fun deriveCandidateSignature(
    summary: StrategyProbeCandidateSummary,
    serviceMode: String?,
): BypassStrategySignature? =
    summary.proxyConfigJson
        ?.takeIf { it.isNotBlank() }
        ?.let { configJson ->
            deriveBypassStrategySignature(
                configJson = configJson,
                routeGroup = null,
                modeOverride = parseServiceModeOrDefault(serviceMode),
            )
        }
