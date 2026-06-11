package com.poyka.ripdpi.data

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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
     * Test #2: DROP_OLDEST event delivery — tryEmit never returns false under load.
     *
     * With the old extraBufferCapacity=8, the 9th emitFailed call would silently return
     * false and the event would be lost. With DROP_OLDEST + capacity 64, tryEmit always
     * succeeds. This test emits 100 events to an active subscriber and asserts that all
     * 100 are delivered in order, with the last reason being reason-99.
     */
    @Test
    fun `all Failed events are delivered in order and none is silently dropped`() =
        runTest {
            val store = DefaultServiceStateStore()

            store.events.test {
                // Emit 100 events with an active Turbine subscriber. Under
                // StandardTestDispatcher, Turbine's unlimited internal channel absorbs all
                // items synchronously — DROP_OLDEST prevents tryEmit from returning false.
                repeat(100) { i ->
                    store.emitFailed(Sender.VPN, FailureReason.NativeError("reason-$i"))
                }

                // All 100 must arrive in emission order.
                val received = mutableListOf<ServiceEvent.Failed>()
                repeat(100) {
                    received += awaitItem() as ServiceEvent.Failed
                }
                cancelAndIgnoreRemainingEvents()

                assertEquals(100, received.size)
                assertEquals(
                    "First event must be reason-0",
                    FailureReason.NativeError("reason-0"),
                    received.first().reason,
                )
                assertEquals(
                    "Last event must be reason-99 (newest always delivered)",
                    FailureReason.NativeError("reason-99"),
                    received.last().reason,
                )
            }
        }
}
