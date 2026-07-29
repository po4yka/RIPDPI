package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyRecordStore
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

            eventStore(durableState).publish(event)
            val reconstructed = eventStore(durableState)

            assertEquals(event, reconstructed.events.first())
            val persisted = durableState.states.values.single()
            assertFalse(persisted.value.contains("policySignature"))
            assertTrue(persisted.value.contains("\"schemaVersion\":3"))

            reconstructed.acknowledge(event.deliveryId)

            assertNull(durableState.getDurableState(persisted.key))
        }

    @Test
    fun `malformed unsupported and stale deliveries are quarantined without blocking valid event`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val store = eventStore(durableState)
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
                    value = validState.value.replace("\"schemaVersion\":3", "\"schemaVersion\":99"),
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

            assertEquals(valid, eventStore(durableState).events.first())

            assertEquals(setOf(validState.key), durableState.states.keys)
        }

    @Test
    fun `pending handover deliveries remain bounded`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val store = eventStore(durableState)

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

            assertEquals(event, eventStore(durableState).events.first())
        }

    @Test
    fun `policy delivery redacts fingerprint and retains dependency until acknowledgement`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val policies = HandoverRememberedPolicyStore()
            val fingerprint = "sensitive-network-hash"
            val policyId = policies.upsertRememberedNetworkPolicy(rememberedPolicy(fingerprint))
            val dependencyKey = "runtime_terminal_policy:session-a"
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(dependencyKey, policyId.toString(), 10L),
            )
            val event =
                handoverEvent("delivery-private").copy(
                    currentFingerprintHash = fingerprint,
                    usedRememberedPolicy = true,
                    rememberedPolicyDependencyKey = dependencyKey,
                )
            val store = eventStore(durableState, policies)

            store.publish(event)

            val persisted = requireNotNull(durableState.states["policy_handover_delivery:${event.deliveryId}"])
            assertFalse(persisted.value.contains(fingerprint))
            assertTrue(persisted.value.contains(dependencyKey))
            assertEquals(event, store.events.first())

            store.acknowledge(event.deliveryId)

            assertNull(durableState.getDurableState(persisted.key))
            assertNull(durableState.getDurableState(dependencyKey))
        }

    @Test
    fun `dependency delivery is quarantined when policy reference is missing or embeds fingerprint`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val policies = HandoverRememberedPolicyStore()
            val fingerprint = "sensitive-network-hash"
            val policyId = policies.upsertRememberedNetworkPolicy(rememberedPolicy(fingerprint))
            val missingDependencyKey = "runtime_terminal_policy:missing"
            val embeddedFingerprintKey = "runtime_terminal_policy:embedded"
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(missingDependencyKey, policyId.toString(), 10L),
            )
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(embeddedFingerprintKey, policyId.toString(), 11L),
            )
            val store = eventStore(durableState, policies)
            val missingDependencyEvent =
                handoverEvent("delivery-missing-dependency").copy(
                    currentFingerprintHash = fingerprint,
                    rememberedPolicyDependencyKey = missingDependencyKey,
                )
            val embeddedFingerprintEvent =
                handoverEvent("delivery-embedded-fingerprint").copy(
                    currentFingerprintHash = fingerprint,
                    rememberedPolicyDependencyKey = embeddedFingerprintKey,
                )
            store.publish(missingDependencyEvent)
            store.publish(embeddedFingerprintEvent)
            durableState.clearDurableStateIfCurrent(missingDependencyKey, policyId.toString())
            val embeddedKey = "policy_handover_delivery:${embeddedFingerprintEvent.deliveryId}"
            val embeddedState = requireNotNull(durableState.states[embeddedKey])
            durableState.upsertDurableState(
                embeddedState.copy(
                    value = embeddedState.value.dropLast(1) + ",\"currentFingerprintHash\":\"$fingerprint\"}",
                ),
            )
            val valid = handoverEvent("delivery-valid-after-quarantine")
            store.publish(valid)

            assertEquals(valid, store.events.first())

            assertNull(durableState.getDurableState("policy_handover_delivery:${missingDependencyEvent.deliveryId}"))
            assertNull(durableState.getDurableState(embeddedKey))
            assertNull(durableState.getDurableState(embeddedFingerprintKey))
        }
}

private fun eventStore(
    durableState: DiagnosticsDurableStateStore,
    policies: RememberedNetworkPolicyRecordStore = HandoverRememberedPolicyStore(),
) = DefaultPolicyHandoverEventStore(durableState, policies)

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

private fun rememberedPolicy(fingerprintHash: String) =
    RememberedNetworkPolicyEntity(
        fingerprintHash = fingerprintHash,
        mode = Mode.VPN.preferenceValue,
        summaryJson = "{}",
        proxyConfigJson = "{}",
        source = "test",
        status = "validated",
        firstObservedAt = 1L,
        updatedAt = 1L,
    )

private class HandoverRememberedPolicyStore : RememberedNetworkPolicyRecordStore {
    private val state = MutableStateFlow<List<RememberedNetworkPolicyEntity>>(emptyList())
    private var nextId = 1L

    override fun observeRememberedNetworkPolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> =
        state.map { it.take(limit) }

    override suspend fun getRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ) = state.value.firstOrNull { it.fingerprintHash == fingerprintHash && it.mode == mode }

    override suspend fun getRememberedNetworkPolicyById(id: Long) = state.value.firstOrNull { it.id == id }

    override suspend fun findValidatedRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ) = getRememberedNetworkPolicy(fingerprintHash, mode)

    override suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long {
        val id = policy.id.takeIf { it > 0L } ?: nextId++
        state.value = state.value.filterNot { it.id == id } + policy.copy(id = id)
        return id
    }

    override suspend fun clearRememberedNetworkPolicies() {
        state.value = emptyList()
    }

    override suspend fun deleteRememberedNetworkPolicy(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun countRememberedNetworkPoliciesForFingerprint(fingerprintHash: String) =
        state.value.count { it.fingerprintHash == fingerprintHash }

    override suspend fun pruneRememberedNetworkPolicies() = Unit
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

    override suspend fun clearDurableStateAndDependencyIfCurrent(
        key: String,
        expectedValue: String,
        dependencyKey: String,
        expectedDependencyValue: String,
    ): Boolean =
        state.value[key]?.value == expectedValue &&
            state.value[dependencyKey]?.value == expectedDependencyValue &&
            clearDurableStateIfCurrent(key, expectedValue) &&
            clearDurableStateIfCurrent(dependencyKey, expectedDependencyValue)

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
