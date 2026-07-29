package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalOutcome
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneCounters
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteDeviceAcceptanceEvidenceWriterTest {
    @Test
    fun `durable writer persists pending start before returning`() =
        runTest {
            val store = RecordingDurableStateStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)
            assertEquals(
                "00000000-0000-0000-0000-00000000000a",
                store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value,
            )

            writer.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted),
            )

            val event = store.nativeEvents.single()
            assertEquals("00000000-0000-0000-0000-00000000000a", event.runtimeId)
            assertEquals("remote_device_acceptance", event.source)
            assertTrue(event.message.contains("run_generation=00000000-0000-0000-0000-00000000000a"))
            assertTrue(event.message.contains("phase=screen_off_started"))
            assertTrue(event.message.contains("outcome=pending"))
            assertEquals(event.id, store.nativeEvents.single().id)
            assertFalse(event.message.contains("ssid", ignoreCase = true))
            assertFalse(event.message.contains("bssid", ignoreCase = true))
            assertFalse(event.message.contains("serial", ignoreCase = true))
        }

    @Test
    fun `durable writer clears pending run after failed or cancelled terminal event`() =
        runTest {
            val store = RecordingDurableStateStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)
            writer.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted),
            )
            writer.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            writer.beginRun("00000000-0000-0000-0000-00000000000b", observedAtMillis = 30L)
            writer.record(
                "00000000-0000-0000-0000-00000000000b",
                backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted),
            )
            writer.record(
                "00000000-0000-0000-0000-00000000000b",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
                ),
            )
            writer.beginRun("00000000-0000-0000-0000-00000000000c", observedAtMillis = 40L)

            assertTrue(store.nativeEvents[1].message.contains("reason=cancelled"))
            assertTrue(store.nativeEvents[3].message.contains("reason=service_stopped"))
            assertFalse(store.nativeEvents.any { it.message.contains("interrupted_before_next_run") })
            assertEquals(
                "00000000-0000-0000-0000-00000000000c",
                store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value,
            )
        }

    @Test
    fun `durable writer keeps pending ledger after screen-off probe pass`() =
        runTest {
            val store = RecordingDurableStateStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)
            writer.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted),
            )
            writer.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Passed,
                ),
            )
            writer.beginRun("00000000-0000-0000-0000-00000000000b", observedAtMillis = 30L)

            val interrupted = store.nativeEvents.last()
            assertEquals("00000000-0000-0000-0000-00000000000a", interrupted.runtimeId)
            assertEquals(30L, interrupted.createdAt)
            assertTrue(interrupted.message.contains("phase=run_interrupted"))
            assertTrue(interrupted.message.contains("reason=interrupted_before_next_run"))
        }

    @Test
    fun `durable writer reconciles pending ledger after writer recreation`() =
        runTest {
            val store = RecordingDurableStateStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
            firstWriter.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)
            firstWriter.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted),
            )

            val recreatedWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
            recreatedWriter.beginRun("00000000-0000-0000-0000-00000000000b", observedAtMillis = 30L)

            val interrupted = store.nativeEvents.last()
            assertEquals("00000000-0000-0000-0000-00000000000a", interrupted.runtimeId)
            assertTrue(interrupted.message.contains("phase=run_interrupted"))
            assertTrue(interrupted.message.contains("reason=interrupted_before_next_run"))
            assertEquals(
                "00000000-0000-0000-0000-00000000000b",
                store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value,
            )
        }

    @Test
    fun `startup reconciliation terminalizes a pending run without replacement`() =
        runTest {
            val store = RecordingDurableStateStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
            firstWriter.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)

            val recreatedWriter =
                DefaultRemoteDeviceAcceptanceEvidenceWriter(
                    durableStateStore = store,
                    wallClock = { 30L },
                )
            recreatedWriter.reconcilePendingRun()

            assertFalse(store.durableStates.containsKey(RemoteAcceptancePendingGenerationKey))
            val interrupted = store.nativeEvents.single()
            assertEquals("00000000-0000-0000-0000-00000000000a", interrupted.runtimeId)
            assertEquals(30L, interrupted.createdAt)
            assertTrue(interrupted.message.contains("phase=run_interrupted"))
            assertTrue(interrupted.message.contains("reason=interrupted_before_startup"))
        }

    @Test
    fun `blocked old nonterminal write cannot replace new generation before startup recovery`() =
        runTest {
            val oldWriteBlocked = CompletableDeferred<Unit>()
            val releaseOldWrite = CompletableDeferred<Unit>()
            val oldGeneration = "00000000-0000-0000-0000-00000000000a"
            val newGeneration = "00000000-0000-0000-0000-00000000000b"
            val store =
                RecordingDurableStateStore(
                    beforeNonterminalCas = { expectedValue ->
                        if (expectedValue == oldGeneration) {
                            oldWriteBlocked.complete(Unit)
                            releaseOldWrite.await()
                        }
                    },
                )
            val oldWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
            oldWriter.beginRun(oldGeneration, observedAtMillis = 10L)
            val delayedOldWrite =
                async {
                    oldWriter.record(
                        oldGeneration,
                        backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted),
                    )
                }
            oldWriteBlocked.await()

            DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
                .beginRun(newGeneration, observedAtMillis = 30L)
            releaseOldWrite.complete(Unit)
            delayedOldWrite.await()

            assertEquals(
                newGeneration,
                store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value,
            )
            assertFalse(
                store.nativeEvents.any { event ->
                    event.runtimeId == oldGeneration && event.message.contains("phase=screen_off_started")
                },
            )

            DefaultRemoteDeviceAcceptanceEvidenceWriter(
                durableStateStore = store,
                wallClock = { 40L },
            ).reconcilePendingRun()

            assertFalse(store.durableStates.containsKey(RemoteAcceptancePendingGenerationKey))
            assertTrue(
                store.nativeEvents.any { event ->
                    event.runtimeId == newGeneration &&
                        event.message.contains("reason=interrupted_before_startup")
                },
            )
        }

    @Test
    fun `startup reconciliation clears malformed generation without emitting it`() =
        runTest {
            val canary = "secret-generation-canary-" + "x".repeat(100_000)
            val store =
                RecordingDurableStateStore().apply {
                    upsertDurableState(
                        DiagnosticsDurableStateEntity(
                            key = RemoteAcceptancePendingGenerationKey,
                            value = canary,
                            updatedAt = 10L,
                        ),
                    )
                }
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.reconcilePendingRun()

            assertFalse(store.durableStates.containsKey(RemoteAcceptancePendingGenerationKey))
            assertTrue(store.nativeEvents.isEmpty())
            assertFalse(store.nativeEvents.any { event -> event.message.contains(canary) })
        }

    @Test
    fun `new run replaces malformed pending generation without terminal evidence`() =
        runTest {
            val canary = "secret-generation-canary"
            val store =
                RecordingDurableStateStore().apply {
                    upsertDurableState(
                        DiagnosticsDurableStateEntity(
                            key = RemoteAcceptancePendingGenerationKey,
                            value = canary,
                            updatedAt = 10L,
                        ),
                    )
                }
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("00000000-0000-0000-0000-00000000000b", observedAtMillis = 30L)

            assertEquals(
                "00000000-0000-0000-0000-00000000000b",
                store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value,
            )
            assertTrue(store.nativeEvents.isEmpty())
            assertFalse(store.nativeEvents.any { event -> event.message.contains(canary) })
        }

    @Test
    fun `cancellation after malformed replacement leaves new generation cancellable`() =
        runTest {
            val replacementCommitted = CompletableDeferred<Unit>()
            val newGeneration = "00000000-0000-0000-0000-00000000000b"
            val store =
                RecordingDurableStateStore(
                    afterReplaceCas = {
                        replacementCommitted.complete(Unit)
                        awaitCancellation()
                    },
                ).apply {
                    upsertDurableState(
                        DiagnosticsDurableStateEntity(
                            key = RemoteAcceptancePendingGenerationKey,
                            value = "malformed-generation",
                            updatedAt = 10L,
                        ),
                    )
                }
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            val begin = launch { writer.beginRun(newGeneration, observedAtMillis = 30L) }
            replacementCommitted.await()
            begin.cancelAndJoin()

            assertEquals(
                newGeneration,
                store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value,
            )

            writer.cancelRun(newGeneration, observedAtMillis = 40L)

            assertFalse(store.durableStates.containsKey(RemoteAcceptancePendingGenerationKey))
            assertTrue(
                store.nativeEvents
                    .single()
                    .message
                    .contains("reason=cancelled"),
            )
        }

    @Test
    fun `durable writer uses deterministic semantic ids`() =
        runTest {
            val firstStore = RecordingDurableStateStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(firstStore)
            val event = backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted)

            firstWriter.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)
            firstWriter.record("00000000-0000-0000-0000-00000000000a", event)
            firstWriter.record("00000000-0000-0000-0000-00000000000a", event)

            val ids = firstStore.nativeEvents.map(NativeSessionEventEntity::id)
            assertEquals(1, ids.size)
            assertNotEquals(ids[0], "00000000-0000-0000-0000-00000000000a")
        }

    @Test
    fun `durable writer clears stale pending ledger when terminal already exists`() =
        runTest {
            val store = RecordingDurableStateStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            firstWriter.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)
            firstWriter.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            store.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = RemoteAcceptancePendingGenerationKey,
                    value = "00000000-0000-0000-0000-00000000000a",
                    updatedAt = 25L,
                ),
            )

            val recreatedWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
            recreatedWriter.beginRun("00000000-0000-0000-0000-00000000000b", observedAtMillis = 30L)

            assertEquals(1, store.nativeEvents.size)
            assertTrue(
                store
                    .nativeEvents
                    .single()
                    .message
                    .contains("phase=after_wake"),
            )
            assertFalse(store.nativeEvents.any { it.message.contains("phase=run_interrupted") })
            assertEquals(
                "00000000-0000-0000-0000-00000000000b",
                store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value,
            )
        }

    @Test
    fun `durable writer preserves the first run-terminal outcome`() =
        runTest {
            val store = RecordingDurableStateStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)
            writer.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            writer.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
                ),
            )

            assertEquals(1, store.nativeEvents.size)
            assertTrue(
                store
                    .nativeEvents
                    .single()
                    .message
                    .contains("reason=cancelled"),
            )
        }

    @Test
    fun `cancellation after committed terminal does not overwrite the result`() =
        runTest {
            val store = RecordingDurableStateStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 10L)
            writer.record(
                "00000000-0000-0000-0000-00000000000a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
                ),
            )
            writer.cancelRun("00000000-0000-0000-0000-00000000000a", observedAtMillis = 30L)

            assertEquals(1, store.nativeEvents.size)
            assertTrue(
                store
                    .nativeEvents
                    .single()
                    .message
                    .contains("reason=service_stopped"),
            )
            assertFalse(
                store
                    .nativeEvents
                    .single()
                    .message
                    .contains("reason=cancelled"),
            )
            assertFalse(store.durableStates.containsKey(RemoteAcceptancePendingGenerationKey))
        }

    private fun backgroundEvent(
        phase: DeviceRuntimeBackgroundSurvivalPhase,
        outcome: DeviceRuntimeBackgroundSurvivalOutcome = DeviceRuntimeBackgroundSurvivalOutcome.Pending,
        reason: DeviceRuntimeBackgroundSurvivalReason? = null,
    ): DeviceRuntimeEvidence.BackgroundSurvival =
        DeviceRuntimeEvidence.BackgroundSurvival(
            mode = Mode.VPN,
            phase = phase,
            outcome = outcome,
            reason = reason,
            countersBefore = DeviceRuntimeDataPlaneCounters(tunnelTxPackets = 1L),
            observedAtMillis = 20L,
        )
}

private class RecordingDurableStateStore(
    private val beforeNonterminalCas: suspend (expectedValue: String) -> Unit = {},
    private val afterReplaceCas: suspend () -> Unit = {},
) : DiagnosticsDurableStateStore {
    val nativeEvents = mutableListOf<NativeSessionEventEntity>()
    val durableStates = mutableMapOf<String, DiagnosticsDurableStateEntity>()

    override suspend fun getDurableState(key: String): DiagnosticsDurableStateEntity? = durableStates[key]

    override fun observeDurableStateByPrefix(keyPrefix: String): Flow<List<DiagnosticsDurableStateEntity>> =
        flowOf(durableStates.values.filter { state -> state.key.startsWith(keyPrefix) })

    override suspend fun upsertDurableState(state: DiagnosticsDurableStateEntity) {
        durableStates[state.key] = state
    }

    override suspend fun upsertBoundedDurableState(
        state: DiagnosticsDurableStateEntity,
        keyPrefix: String,
        minimumUpdatedAt: Long,
        retainCount: Int,
    ) {
        durableStates[state.key] = state
        durableStates.entries.removeAll { (key, value) ->
            key.startsWith(keyPrefix) && value.updatedAt < minimumUpdatedAt
        }
        val retainedKeys =
            durableStates.values
                .filter { value -> value.key.startsWith(keyPrefix) }
                .sortedWith(
                    compareByDescending<DiagnosticsDurableStateEntity> { it.updatedAt }
                        .thenByDescending { it.key },
                ).take(retainCount)
                .mapTo(mutableSetOf()) { it.key }
        durableStates.keys.removeAll { key -> key.startsWith(keyPrefix) && key !in retainedKeys }
    }

    override suspend fun clearDurableStateIfCurrent(
        key: String,
        expectedValue: String,
    ): Boolean =
        if (durableStates[key]?.value == expectedValue) {
            durableStates.remove(key)
            true
        } else {
            false
        }

    override suspend fun replaceDurableStateIfCurrent(
        state: DiagnosticsDurableStateEntity,
        expectedValue: String,
    ): Boolean {
        if (durableStates[state.key]?.value != expectedValue) return false
        durableStates[state.key] = state
        afterReplaceCas()
        return true
    }

    override suspend fun insertNativeSessionEventAndUpsertDurableState(
        event: NativeSessionEventEntity,
        state: DiagnosticsDurableStateEntity,
    ) {
        nativeEvents.removeAll { existing -> existing.id == event.id }
        nativeEvents += event
        durableStates[state.key] = state
    }

    override suspend fun insertNativeSessionEventAndUpsertDurableStateIfCurrent(
        event: NativeSessionEventEntity,
        state: DiagnosticsDurableStateEntity,
        expectedValue: String,
    ): Boolean {
        beforeNonterminalCas(expectedValue)
        if (durableStates[state.key]?.value != expectedValue) return false
        insertNativeSessionEventAndUpsertDurableState(event, state)
        return true
    }

    override suspend fun insertNativeSessionEventAndClearDurableState(
        event: NativeSessionEventEntity,
        key: String,
        expectedValue: String,
    ) {
        nativeEvents.removeAll { existing -> existing.id == event.id }
        nativeEvents += event
        if (durableStates[key]?.value == expectedValue) {
            durableStates.remove(key)
        }
    }

    override suspend fun insertNativeSessionEventAndClearDurableStateIfCurrent(
        event: NativeSessionEventEntity,
        key: String,
        expectedValue: String,
    ): Boolean =
        if (durableStates[key]?.value == expectedValue) {
            insertNativeSessionEventAndClearDurableState(event, key, expectedValue)
            true
        } else {
            false
        }

    override suspend fun reconcileDurableStateWithTerminalEvent(
        key: String,
        expectedValue: String,
        replacementState: DiagnosticsDurableStateEntity,
        terminalEventId: String,
        missingTerminalEvent: NativeSessionEventEntity,
    ) {
        if (durableStates[key]?.value == expectedValue) {
            if (nativeEvents.none { event -> event.id == terminalEventId }) {
                nativeEvents.removeAll { existing -> existing.id == missingTerminalEvent.id }
                nativeEvents += missingTerminalEvent
            }
            durableStates.remove(key)
        }
        durableStates[replacementState.key] = replacementState
    }
}
