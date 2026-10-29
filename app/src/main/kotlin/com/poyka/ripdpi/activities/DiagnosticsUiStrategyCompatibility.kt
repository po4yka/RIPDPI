package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.diagnostics.StrategySignaturePresenter as StrategySignaturePresenterDelegate
import com.poyka.ripdpi.ui.diagnostics.toStrategyProbeReportUiModel as toStrategyProbeReportUiModelDelegate

internal fun DiagnosticsUiFactorySupport.toStrategyProbeReportUiModel(
    report: StrategyProbeReport,
    reportResults: List<ProbeResult>,
    serviceMode: String?,
    sessionId: String? = null,
): DiagnosticsStrategyProbeReportUiModel =
    this.toStrategyProbeReportUiModelDelegate(
        report = report,
        reportResults = reportResults,
        serviceMode = serviceMode,
        sessionId = sessionId,
    )

internal class StrategySignaturePresenter(
    stringResolver: StringResolver,
) {
    private val delegate = StrategySignaturePresenterDelegate(stringResolver)

    fun fields(signature: BypassStrategySignature): List<DiagnosticsFieldUiModel> = delegate.fields(signature)
}
