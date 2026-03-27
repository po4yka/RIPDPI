package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.SharedFlow

data class PolicyHandoverEvent(
    val mode: Mode,
    val previousFingerprintHash: String? = null,
    val currentFingerprintHash: String,
    val classification: String,
    val currentNetworkValidated: Boolean,
    val currentCaptivePortalDetected: Boolean,
    val usedRememberedPolicy: Boolean,
    val policySignature: String,
    val occurredAt: Long,
)

interface PolicyHandoverEventStore {
    val events: SharedFlow<PolicyHandoverEvent>

    fun publish(event: PolicyHandoverEvent)
}
