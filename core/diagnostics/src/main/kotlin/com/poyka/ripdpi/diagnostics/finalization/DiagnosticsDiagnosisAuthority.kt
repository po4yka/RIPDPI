@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire

internal object DiagnosticsDiagnosisAuthority {
    internal const val RustEngineAuthority = "rust_engine"

    internal fun finalizeReport(rawReport: EngineScanReportWire): EngineScanReportWire = rawReport
}
