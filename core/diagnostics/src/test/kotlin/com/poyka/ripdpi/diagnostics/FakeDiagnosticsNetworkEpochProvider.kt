package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DiagnosticsNetworkEpoch
import com.poyka.ripdpi.data.DiagnosticsNetworkEpochProvider

internal data class FakeDiagnosticsNetworkEpoch(
    val generation: Long = 1,
) : DiagnosticsNetworkEpoch

internal class FakeDiagnosticsNetworkEpochProvider(
    var epoch: DiagnosticsNetworkEpoch? = FakeDiagnosticsNetworkEpoch(),
) : DiagnosticsNetworkEpochProvider {
    override fun capture(): DiagnosticsNetworkEpoch? = epoch
}
