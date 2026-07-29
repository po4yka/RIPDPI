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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

            writer.beginRun("run-a", observedAtMillis = 10L)
            assertEquals("run-a", store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value)

            writer.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))

            val event = store.nativeEvents.single()
            assertEquals("run-a", event.runtimeId)
            assertEquals("remote_device_acceptance", event.source)
            assertTrue(event.message.contains("run_generation=run-a"))
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

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))
            writer.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            writer.beginRun("run-b", observedAtMillis = 30L)
            writer.record("run-b", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))
            writer.record(
                "run-b",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
                ),
            )
            writer.beginRun("run-c", observedAtMillis = 40L)

            assertTrue(store.nativeEvents[1].message.contains("reason=cancelled"))
            assertTrue(store.nativeEvents[3].message.contains("reason=service_stopped"))
            assertFalse(store.nativeEvents.any { it.message.contains("interrupted_before_next_run") })
            assertEquals("run-c", store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value)
        }

    @Test
    fun `durable writer keeps pending ledger after screen-off probe pass`() =
        runTest {
            val store = RecordingDurableStateStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))
            writer.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Passed,
                ),
            )
            writer.beginRun("run-b", observedAtMillis = 30L)

            val interrupted = store.nativeEvents.last()
            assertEquals("run-a", interrupted.runtimeId)
            assertEquals(30L, interrupted.createdAt)
            assertTrue(interrupted.message.contains("phase=run_interrupted"))
            assertTrue(interrupted.message.contains("reason=interrupted_before_next_run"))
        }

    @Test
    fun `durable writer reconciles pending ledger after writer recreation`() =
        runTest {
            val store = RecordingDurableStateStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
            firstWriter.beginRun("run-a", observedAtMillis = 10L)
            firstWriter.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))

            val recreatedWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
            recreatedWriter.beginRun("run-b", observedAtMillis = 30L)

            val interrupted = store.nativeEvents.last()
            assertEquals("run-a", interrupted.runtimeId)
            assertTrue(interrupted.message.contains("phase=run_interrupted"))
            assertTrue(interrupted.message.contains("reason=interrupted_before_next_run"))
            assertEquals("run-b", store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value)
        }

    @Test
    fun `durable writer uses deterministic semantic ids`() =
        runTest {
            val firstStore = RecordingDurableStateStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(firstStore)
            val event = backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted)

            firstWriter.beginRun("run-a", observedAtMillis = 10L)
            firstWriter.record("run-a", event)
            firstWriter.record("run-a", event)

            val ids = firstStore.nativeEvents.map(NativeSessionEventEntity::id)
            assertEquals(1, ids.size)
            assertNotEquals(ids[0], "run-a")
        }

    @Test
    fun `durable writer clears stale pending ledger when terminal already exists`() =
        runTest {
            val store = RecordingDurableStateStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            firstWriter.beginRun("run-a", observedAtMillis = 10L)
            firstWriter.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            store.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = RemoteAcceptancePendingGenerationKey,
                    value = "run-a",
                    updatedAt = 25L,
                ),
            )

            val recreatedWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)
            recreatedWriter.beginRun("run-b", observedAtMillis = 30L)

            assertEquals(1, store.nativeEvents.size)
            assertTrue(
                store
                    .nativeEvents
                    .single()
                    .message
                    .contains("phase=after_wake"),
            )
            assertFalse(store.nativeEvents.any { it.message.contains("phase=run_interrupted") })
            assertEquals("run-b", store.durableStates.getValue(RemoteAcceptancePendingGenerationKey).value)
        }

    @Test
    fun `durable writer preserves the first run-terminal outcome`() =
        runTest {
            val store = RecordingDurableStateStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            writer.record(
                "run-a",
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

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
                ),
            )
            writer.cancelRun("run-a", observedAtMillis = 30L)

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

private class RecordingDurableStateStore : DiagnosticsDurableStateStore {
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

    override suspend fun insertNativeSessionEventAndUpsertDurableState(
        event: NativeSessionEventEntity,
        state: DiagnosticsDurableStateEntity,
    ) {
        nativeEvents.removeAll { existing -> existing.id == event.id }
        nativeEvents += event
        durableStates[state.key] = state
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
