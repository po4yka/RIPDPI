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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyHandoverEventStoreTest {
    @Test
    fun `pending delivery survives store reconstruction until acknowledged`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val event = handoverEvent("delivery-stable")

            DefaultPolicyHandoverEventStore(durableState).publish(event)
            val reconstructed = DefaultPolicyHandoverEventStore(durableState)

            assertEquals(event, reconstructed.events.first())
            val persisted = durableState.states.values.single()
            assertFalse(persisted.value.contains("policySignature"))
            assertTrue(persisted.value.contains("\"schemaVersion\":2"))

            reconstructed.acknowledge(event.deliveryId)

            assertNull(durableState.getDurableState(persisted.key))
        }

    @Test
    fun `malformed unsupported and stale deliveries are quarantined without blocking valid event`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val store = DefaultPolicyHandoverEventStore(durableState)
            val valid = handoverEvent("delivery-valid")
            store.publish(valid)
            val validState = durableState.states.values.single()
            durableState.upsertDurableState(
                validState.copy(
                    key = "policy_handover_delivery:malformed",
                    value = "{not-json-sensitive-canary",
                    updatedAt = validState.updatedAt - 1L,
                ),
            )
            durableState.upsertDurableState(
                validState.copy(
                    key = "policy_handover_delivery:unsupported",
                    value = validState.value.replace("\"schemaVersion\":2", "\"schemaVersion\":99"),
                    updatedAt = validState.updatedAt - 1L,
                ),
            )
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = "policy_handover_delivery:stale",
                    value = Json.encodeToString(handoverEvent("stale")),
                    updatedAt = 1L,
                ),
            )

            assertEquals(valid, DefaultPolicyHandoverEventStore(durableState).events.first())

            assertEquals(setOf(validState.key), durableState.states.keys)
        }

    @Test
    fun `pending handover deliveries remain bounded`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val store = DefaultPolicyHandoverEventStore(durableState)

            repeat(66) { index -> store.publish(handoverEvent("delivery-$index")) }

            assertEquals(64, durableState.states.size)
        }

    @Test
    fun `legacy direct delivery remains readable`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val event = handoverEvent("delivery-legacy")
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = "policy_handover_delivery:${event.deliveryId}",
                    value = Json.encodeToString(event),
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            assertEquals(event, DefaultPolicyHandoverEventStore(durableState).events.first())
        }
}

private fun handoverEvent(deliveryId: String) =
    PolicyHandoverEvent(
        deliveryId = deliveryId,
        mode = Mode.VPN,
        currentFingerprintHash = "fingerprint-a",
        classification = "transport_switch",
        currentNetworkValidated = true,
        currentCaptivePortalDetected = false,
        usedRememberedPolicy = false,
        occurredAt = 100L,
    )

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

    override suspend fun upsertBoundedDurableState(
        state: DiagnosticsDurableStateEntity,
        keyPrefix: String,
        minimumUpdatedAt: Long,
        retainCount: Int,
    ) {
        this.state.value += state.key to state
        this.state.value =
            this.state.value
                .filterValues { entry -> !entry.key.startsWith(keyPrefix) || entry.updatedAt >= minimumUpdatedAt }
                .let { entries ->
                    val retainedKeys =
                        entries.values
                            .filter { entry -> entry.key.startsWith(keyPrefix) }
                            .sortedWith(
                                compareByDescending<DiagnosticsDurableStateEntity> { it.updatedAt }
                                    .thenByDescending { it.key },
                            ).take(retainCount)
                            .mapTo(mutableSetOf()) { it.key }
                    entries.filterKeys { key -> !key.startsWith(keyPrefix) || key in retainedKeys }
                }
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
