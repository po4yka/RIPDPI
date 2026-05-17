package com.poyka.ripdpi.service.runtime.vpn

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.services.ConnectionPolicyResolution

internal fun ConnectionPolicyResolution.toVpnActiveConnectionPolicy(
    restartReason: String,
    appliedAt: Long,
): ActiveConnectionPolicy? {
    val policy = appliedPolicy ?: return null
    return ActiveConnectionPolicy(
        mode = Mode.VPN,
        policy = policy,
        matchedPolicy = matchedNetworkPolicy,
        usedRememberedPolicy = matchedNetworkPolicy != null,
        rememberedPolicyAppliedByExactMatch = rememberedPolicyAppliedByExactMatch,
        fingerprintHash = fingerprintHash,
        policySignature = policySignature,
        appliedAt = appliedAt,
        restartReason = restartReason,
        handoverClassification = handoverClassification,
    )
}
