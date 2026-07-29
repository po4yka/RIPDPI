package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyRecordStore
import com.poyka.ripdpi.data.policyHandoverDeliveryId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PolicyHandoverEventStoreTest {
    @Test
    fun `pending delivery survives store reconstruction until acknowledged`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val event = handoverEvent("delivery-stable")

            eventStore(durableState).publish(event)
            val reconstructed = eventStore(durableState)

            assertTrue(reconstructed.isPending(event.deliveryId))
            assertEquals(event, reconstructed.events.first())
            val persisted = durableState.states.values.single()
            assertFalse(persisted.value.contains("policySignature"))
            assertTrue(persisted.value.contains("\"schemaVersion\":3"))

            reconstructed.acknowledge(event.deliveryId)

            assertFalse(reconstructed.isPending(event.deliveryId))
            assertNull(durableState.getDurableState(persisted.key))
        }

    @Test
    fun `pending check evicts stale delivery and its dependency`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val policies = HandoverRememberedPolicyStore()
            val fingerprint = "fingerprint-stale"
            val policyId = policies.upsertRememberedNetworkPolicy(rememberedPolicy(fingerprint))
            val sessionId = "ebcb4eec-285d-4b30-a332-bff526c81151"
            val dependencyKey = "runtime_terminal_policy:$sessionId"
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(dependencyKey, policyId.toString(), 10L),
            )
            val event =
                dependentHandoverEvent(sessionId, fingerprint)
            val store = eventStore(durableState, policies)
            store.publish(event)
            val deliveryKey = "policy_handover_delivery:${event.deliveryId}"
            val delivery = requireNotNull(durableState.states[deliveryKey])
            durableState.upsertDurableState(
                delivery.copy(updatedAt = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1_000L),
            )

            assertFalse(store.isPending(event.deliveryId))
            assertNull(durableState.getDurableState(deliveryKey))
            assertNull(durableState.getDurableState(dependencyKey))
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
    fun `count and age eviction clear retained policy dependencies`() =
        runTest {
            val durableState = HandoverDurableStateStore()
            val policies = HandoverRememberedPolicyStore()
            val store = eventStore(durableState, policies)
            repeat(65) { index ->
                val fingerprint = "fingerprint-$index"
                val policyId = policies.upsertRememberedNetworkPolicy(rememberedPolicy(fingerprint))
                val sessionId = namedSessionId("bounded-$index")
                val dependencyKey = "runtime_terminal_policy:$sessionId"
                durableState.upsertDurableState(
                    DiagnosticsDurableStateEntity(dependencyKey, policyId.toString(), index.toLong()),
                )
                store.publish(
                    dependentHandoverEvent(sessionId, fingerprint),
                )
            }

            assertEquals(
                64,
                durableState.states.keys.count { key -> key.startsWith("policy_handover_delivery:") },
            )
            assertEquals(
                64,
                durableState.states.keys.count { key -> key.startsWith("runtime_terminal_policy:") },
            )

            val staleDelivery =
                durableState.states.values
                    .first { state -> state.key.startsWith("policy_handover_delivery:") }
            val staleEnvelope = Json.parseToJsonElement(staleDelivery.value).jsonObject
            val staleDependencyKey =
                requireNotNull(staleEnvelope["rememberedPolicyDependencyKey"]?.jsonPrimitive?.content)
            durableState.upsertDurableState(
                staleDelivery.copy(updatedAt = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1_000L),
            )

            store.publish(handoverEvent("delivery-after-stale"))

            assertNull(durableState.getDurableState(staleDelivery.key))
            assertNull(durableState.getDurableState(staleDependencyKey))
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
            val sessionId = "41373290-a298-48cf-b231-680cf0af6f47"
            val dependencyKey = "runtime_terminal_policy:$sessionId"
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(dependencyKey, policyId.toString(), 10L),
            )
            val event =
                dependentHandoverEvent(sessionId, fingerprint)
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
            val missingSessionId = "f793b542-774d-4a32-a951-7e7bf70f32f6"
            val embeddedSessionId = "90c4bb6e-b867-423b-8564-b6c562dc7e9e"
            val missingDependencyKey = "runtime_terminal_policy:$missingSessionId"
            val embeddedFingerprintKey = "runtime_terminal_policy:$embeddedSessionId"
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(missingDependencyKey, policyId.toString(), 10L),
            )
            durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(embeddedFingerprintKey, policyId.toString(), 11L),
            )
            val store = eventStore(durableState, policies)
            val missingDependencyEvent =
                dependentHandoverEvent(missingSessionId, fingerprint)
            val embeddedFingerprintEvent =
                dependentHandoverEvent(embeddedSessionId, fingerprint)
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
            assertNotNull(durableState.getDurableState(embeddedFingerprintKey))
        }

    @Test
    fun `acknowledgement never deletes dependencies from untrusted envelopes`() =
        runTest {
            val sessionId = "a0dcc56f-0532-49ea-afef-c168d3509ef6"
            val validDependencyKey = "runtime_terminal_policy:$sessionId"
            val otherSessionId = "4674580a-5e63-450a-87d1-959a7a37e2eb"
            val untrustedCases =
                listOf(
                    "terminal outbox key" to "runtime_terminal_outbox:$sessionId",
                    "percent wildcard" to "runtime_terminal_policy:%",
                    "underscore wildcard" to "runtime_terminal_policy:_",
                    "prefix variant" to "runtime_terminal_policy_evil:$sessionId",
                    "mismatched session" to "runtime_terminal_policy:$otherSessionId",
                )

            untrustedCases.forEach { (label, dependencyKey) ->
                val fixture = dependencyFixture(sessionId, validDependencyKey)
                fixture.durableState.upsertDurableState(
                    DiagnosticsDurableStateEntity(dependencyKey, fixture.policyId.toString(), 11L),
                )
                val tampered =
                    fixture.persisted.copy(
                        value = fixture.persisted.value.replace(validDependencyKey, dependencyKey),
                    )
                fixture.durableState.upsertDurableState(tampered)

                fixture.store.acknowledge(fixture.event.deliveryId)

                assertNull(label, fixture.durableState.getDurableState(tampered.key))
                assertNotNull(label, fixture.durableState.getDurableState(dependencyKey))
            }
        }

    @Test
    fun `acknowledgement never deletes dependency for unsupported or mismatched envelope`() =
        runTest {
            val sessionId = "c549e43d-2efb-4420-a970-46b3e964ea4d"
            val dependencyKey = "runtime_terminal_policy:$sessionId"
            val mutations =
                listOf<(String) -> String>(
                    { value -> value.replace("\"schemaVersion\":3", "\"schemaVersion\":99") },
                    { value ->
                        value.replace(
                            policyHandoverDeliveryId("runtime-terminal:$sessionId"),
                            "other-delivery",
                        )
                    },
                )

            mutations.forEach { mutate ->
                val fixture = dependencyFixture(sessionId, dependencyKey)
                val tampered = fixture.persisted.copy(value = mutate(fixture.persisted.value))
                fixture.durableState.upsertDurableState(tampered)

                fixture.store.acknowledge(fixture.event.deliveryId)

                assertNull(fixture.durableState.getDurableState(tampered.key))
                assertNotNull(fixture.durableState.getDurableState(dependencyKey))
            }
        }

    @Test
    fun `stale corrupt delivery pruning retains unrelated durable state`() =
        runTest {
            val sessionId = "8e2f490f-dd59-4fe7-b737-6963464086d8"
            val validDependencyKey = "runtime_terminal_policy:$sessionId"
            val fixture = dependencyFixture(sessionId, validDependencyKey)
            val terminalOutboxKey = "runtime_terminal_outbox:$sessionId"
            fixture.durableState.upsertDurableState(
                DiagnosticsDurableStateEntity(terminalOutboxKey, "terminal-marker", 20L),
            )
            fixture.durableState.upsertDurableState(
                fixture.persisted.copy(
                    value = fixture.persisted.value.replace(validDependencyKey, terminalOutboxKey),
                    updatedAt = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1_000L,
                ),
            )

            fixture.store.publish(handoverEvent("delivery-after-corrupt"))

            assertNull(fixture.durableState.getDurableState(fixture.persisted.key))
            assertNotNull(fixture.durableState.getDurableState(terminalOutboxKey))
            assertNotNull(fixture.durableState.getDurableState(validDependencyKey))
        }
}

private suspend fun dependencyFixture(
    sessionId: String,
    dependencyKey: String,
): HandoverDependencyFixture {
    val durableState = HandoverDurableStateStore()
    val policies = HandoverRememberedPolicyStore()
    val fingerprint = "fingerprint-$sessionId"
    val policyId = policies.upsertRememberedNetworkPolicy(rememberedPolicy(fingerprint))
    durableState.upsertDurableState(DiagnosticsDurableStateEntity(dependencyKey, policyId.toString(), 10L))
    val event = dependentHandoverEvent(sessionId, fingerprint)
    val store = eventStore(durableState, policies)
    store.publish(event)
    return HandoverDependencyFixture(
        durableState = durableState,
        store = store,
        event = event,
        persisted = requireNotNull(durableState.states["policy_handover_delivery:${event.deliveryId}"]),
        policyId = policyId,
    )
}

private data class HandoverDependencyFixture(
    val durableState: HandoverDurableStateStore,
    val store: DefaultPolicyHandoverEventStore,
    val event: PolicyHandoverEvent,
    val persisted: DiagnosticsDurableStateEntity,
    val policyId: Long,
)

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

private fun dependentHandoverEvent(
    sessionId: String,
    fingerprint: String,
) = handoverEvent(policyHandoverDeliveryId("runtime-terminal:$sessionId")).copy(
    currentFingerprintHash = fingerprint,
    usedRememberedPolicy = true,
    rememberedPolicyDependencyKey = "runtime_terminal_policy:$sessionId",
)

private fun namedSessionId(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

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
