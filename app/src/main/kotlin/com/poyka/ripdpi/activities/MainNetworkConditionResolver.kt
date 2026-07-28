package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence
import com.poyka.ripdpi.services.network.NetworkCondition
import com.poyka.ripdpi.services.network.decideNetworkCondition

internal fun resolveMainNetworkCondition(
    appStatus: AppStatus,
    evidence: NetworkPathValidationEvidence?,
): NetworkCondition {
    val hasUsableNetwork =
        when {
            evidence?.captureStatus != "captured" -> true
            evidence.underlayPresent == false -> false
            evidence.underlayInternet == false -> false
            evidence.underlayValidated == false -> false
            evidence.underlayCaptivePortal == true -> false
            else -> true
        }
    return decideNetworkCondition(
        hasUsableNetwork = hasUsableNetwork,
        lifecycleBlockedOrReconnecting = appStatus == AppStatus.Reconnecting,
        captivePortalAssist = null,
        whitelistEvidence = null,
        hasWhitelistRelayProfile = false,
        nowMillis = 0L,
    )
}
