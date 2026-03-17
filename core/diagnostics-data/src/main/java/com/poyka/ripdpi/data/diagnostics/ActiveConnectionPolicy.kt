package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

data class ActiveConnectionPolicy(
    val mode: Mode,
    val policy: RememberedNetworkPolicyJson,
    val matchedPolicy: RememberedNetworkPolicyEntity? = null,
    val usedRememberedPolicy: Boolean = false,
    val fingerprintHash: String? = null,
    val policySignature: String,
    val appliedAt: Long,
    val restartReason: String = "initial_start",
    val handoverClassification: String? = null,
)

interface ActiveConnectionPolicyStore {
    val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>>

    fun current(mode: Mode): ActiveConnectionPolicy? = activePolicies.value[mode]

    fun observe(mode: Mode): Flow<ActiveConnectionPolicy?> = activePolicies.map { policies -> policies[mode] }
}
