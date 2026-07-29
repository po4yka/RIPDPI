package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PolicyHandoverEventStoreTest {
    @Test
    fun `pending delivery survives store reconstruction until acknowledged`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val event =
                PolicyHandoverEvent(
                    deliveryId = "delivery-stable",
                    mode = Mode.VPN,
                    currentFingerprintHash = "fingerprint-a",
                    classification = "transport_switch",
                    currentNetworkValidated = true,
                    currentCaptivePortalDetected = false,
                    usedRememberedPolicy = false,
                    occurredAt = 100L,
                )

            DefaultPolicyHandoverEventStore(durableState).publish(event)
            val reconstructed = DefaultPolicyHandoverEventStore(durableState)

            assertEquals(event, reconstructed.events.first())
            val persisted = durableState.states.values.single()
            assertFalse(persisted.value.contains("policySignature"))

            reconstructed.acknowledge(event.deliveryId)

            assertNull(durableState.getDurableState(persisted.key))
        }
}

private class HandoverDurableStateStore : DiagnosticsDurableStateStore {
    private val state = MutableStateFlow<Map<String, DiagnosticsDurableStateEntity>>(emptyMap())
    val states: Map<String, DiagnosticsDurableStateEntity>
        get() = state.value

    override suspend fun getDurableState(key: String): DiagnosticsDurableStateEntity? = state.value[key]

    override fun observeDurableStateByPrefix(keyPrefix: String): Flow<List<DiagnosticsDurableStateEntity>> =
        state.map { states ->
            states.values
                .filter { entry -> entry.key.startsWith(keyPrefix) }
                .sortedBy(DiagnosticsDurableStateEntity::updatedAt)
        }

    override suspend fun upsertDurableState(state: DiagnosticsDurableStateEntity) {
        this.state.value += state.key to state
    }

    override suspend fun clearDurableStateIfCurrent(
        key: String,
        expectedValue: String,
    ): Boolean {
        if (state.value[key]?.value != expectedValue) return false
        state.value -= key
        return true
    }

    override suspend fun insertNativeSessionEventAndUpsertDurableState(
        event: NativeSessionEventEntity,
        state: DiagnosticsDurableStateEntity,
    ) = unsupported()

    override suspend fun insertNativeSessionEventAndClearDurableState(
        event: NativeSessionEventEntity,
        key: String,
        expectedValue: String,
    ) = unsupported()

    override suspend fun insertNativeSessionEventAndClearDurableStateIfCurrent(
        event: NativeSessionEventEntity,
        key: String,
        expectedValue: String,
    ): Boolean = unsupported()

    override suspend fun reconcileDurableStateWithTerminalEvent(
        key: String,
        expectedValue: String,
        replacementState: DiagnosticsDurableStateEntity,
        terminalEventId: String,
        missingTerminalEvent: NativeSessionEventEntity,
    ) = unsupported()

    private fun unsupported(): Nothing = error("Not used by policy handover tests")
}
