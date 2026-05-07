package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.ui.diagnostics.StrategySignaturePresenter as StrategySignaturePresenterDelegate
import com.poyka.ripdpi.ui.diagnostics.toStrategyProbeReportUiModel as toStrategyProbeReportUiModelDelegate

internal fun DiagnosticsUiFactorySupport.toStrategyProbeReportUiModel(
    report: StrategyProbeReport,
    reportResults: List<ProbeResult>,
    serviceMode: String?,
): DiagnosticsStrategyProbeReportUiModel =
    this.toStrategyProbeReportUiModelDelegate(
        report = report,
        reportResults = reportResults,
        serviceMode = serviceMode,
    )

internal class StrategySignaturePresenter {
    private val delegate = StrategySignaturePresenterDelegate()

    fun fields(signature: BypassStrategySignature): List<DiagnosticsFieldUiModel> = delegate.fields(signature)
}
