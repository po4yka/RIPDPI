package com.poyka.ripdpi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStateStoreConcurrencyTest {
    /**
     * Test #1: Status/telemetry coherence under concurrent setStatus calls.
     *
     * Two coroutines on Dispatchers.Default race 1000 setStatus calls each, alternating
     * Running/Halted. After both join, the telemetry.status field must equal
     * status.value.first because setStatus writes both atomically under the lock.
     * updateTelemetry is NOT mixed in here — it legitimately writes an independent status
     * field and is tested separately.
     */
    @Test
    fun `status and telemetry status remain coherent under concurrent setStatus calls`() =
        runTest {
            val store = DefaultServiceStateStore()

            val writer1 =
                launch(Dispatchers.Default) {
                    repeat(1000) { i ->
                        store.setStatus(
                            if (i % 2 == 0) AppStatus.Running else AppStatus.Halted,
                            Mode.VPN,
                        )
                    }
                }

            val writer2 =
                launch(Dispatchers.Default) {
                    repeat(1000) { i ->
                        store.setStatus(
                            if (i % 3 == 0) AppStatus.Running else AppStatus.Halted,
                            Mode.Proxy,
                        )
                    }
                }

            writer1.join()
            writer2.join()

            // setStatus writes _status and _telemetry.status atomically under the lock.
            // After both writers complete the two fields must be consistent.
            val finalStatus = store.status.value.first
            val finalTelemetryStatus = store.telemetry.value.status
            assertEquals(
                "telemetry.status must match status.value.first after concurrent setStatus calls",
                finalStatus,
                finalTelemetryStatus,
            )
        }

    /**
     * Test #2: event delivery remains ordered when producers run before a lifecycle subscriber.
     */
    @Test
    fun `all Failed events are delivered in order and none is silently dropped`() =
        runTest {
            val store = DefaultServiceStateStore()
            val received = mutableListOf<ServiceEvent>()

            repeat(100) { i ->
                store.emitFailed(Sender.VPN, FailureReason.NativeError("reason-$i"))
            }
            runCurrent()

            val collector =
                launch {
                    store.events
                        .onEach { delay(1) }
                        .take(100)
                        .toList(received)
                }
            runCurrent()
            collector.join()

            val failures = received.map { it as ServiceEvent.Failed }

            assertEquals(100, failures.size)
            assertEquals(
                "First event must be reason-0",
                FailureReason.NativeError("reason-0"),
                failures.first().reason,
            )
            assertEquals(
                "Last event must be reason-99",
                FailureReason.NativeError("reason-99"),
                failures.last().reason,
            )
        }

    @Test
    fun `Failed event preserves runtime state from publication time`() =
        runTest {
            val store = DefaultServiceStateStore()
            store.setStatus(AppStatus.Running, Mode.VPN)
            store.emitFailed(Sender.VPN, FailureReason.NativeError("relay failed"))
            store.setStatus(AppStatus.Halted, Mode.VPN)

            val failure =
                store.events
                    .take(1)
                    .toList()
                    .single() as ServiceEvent.Failed

            assertEquals(AppStatus.Running, failure.statusAtFailure)
            assertEquals(Mode.VPN, failure.modeAtFailure)
            assertEquals(AppStatus.Halted to Mode.VPN, store.status.value)
        }

    @Test
    fun `history events preserve failure before terminal status`() =
        runTest {
            val store = DefaultServiceStateStore()
            store.setStatus(AppStatus.Running, Mode.VPN)
            store.emitFailed(Sender.Proxy, FailureReason.NativeError("boom"))
            store.setStatus(AppStatus.Halted, Mode.VPN)

            val history = store.historyEvents.take(4).toList()

            assertEquals(
                listOf(
                    ServiceHistoryEvent.StatusChanged(AppStatus.Halted, Mode.VPN),
                    ServiceHistoryEvent.StatusChanged(AppStatus.Running, Mode.VPN),
                    ServiceHistoryEvent.Failed(
                        ServiceEvent.Failed(
                            sender = Sender.Proxy,
                            reason = FailureReason.NativeError("boom"),
                            statusAtFailure = AppStatus.Running,
                            modeAtFailure = Mode.VPN,
                        ),
                    ),
                    ServiceHistoryEvent.StatusChanged(AppStatus.Halted, Mode.VPN),
                ),
                history,
            )
        }
}
