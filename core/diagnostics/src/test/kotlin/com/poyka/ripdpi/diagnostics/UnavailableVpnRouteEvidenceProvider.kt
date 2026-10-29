package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import kotlinx.coroutines.flow.MutableStateFlow

internal object UnavailableVpnRouteEvidenceProvider : VpnRouteEvidenceProvider {
    override val changes = MutableStateFlow(0L)

    override fun capture(): VpnRouteEvidence = VpnRouteEvidence()
}
