package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class PolicyHandoverEvent(
    val deliveryId: String,
    val mode: Mode,
    val previousFingerprintHash: String? = null,
    val currentFingerprintHash: String,
    val classification: String,
    val currentNetworkValidated: Boolean,
    val currentCaptivePortalDetected: Boolean,
    val usedRememberedPolicy: Boolean,
    val occurredAt: Long,
    val rememberedPolicyDependencyKey: String? = null,
)

interface PolicyHandoverEventStore {
    val events: Flow<PolicyHandoverEvent>

    suspend fun publish(event: PolicyHandoverEvent)

    suspend fun acknowledge(deliveryId: String)

    suspend fun isPending(deliveryId: String): Boolean
}

fun policyHandoverDeliveryId(seed: String): String =
    "policy-handover:" + UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8))

fun policyHandoverScanSessionId(deliveryId: String): String =
    UUID.nameUUIDFromBytes("diagnostics-scan:$deliveryId".toByteArray(StandardCharsets.UTF_8)).toString()
