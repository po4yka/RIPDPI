package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.xray.XrayProviderProbeReport
import com.poyka.ripdpi.data.xray.XrayProviderTelemetrySummaries

internal fun ServiceStateStore.currentXrayExportContext(): String? =
    telemetry.value.xrayProviderSnapshot?.let { snapshot ->
        "Current provider at export time (not historical scan evidence):\n" +
            XrayProviderTelemetrySummaries.export(XrayProviderProbeReport(snapshot))
    }
