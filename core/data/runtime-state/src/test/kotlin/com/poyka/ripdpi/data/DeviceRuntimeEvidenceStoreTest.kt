package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRuntimeEvidenceStoreTest {
    @Test
    fun `buffers evidence before collector and drops oldest at capacity`() =
        runTest {
            val store = DefaultDeviceRuntimeEvidenceStore()
            repeat(DeviceRuntimeEvidenceCapacity + 3) { index ->
                store.record(
                    DeviceRuntimeEvidence.ServiceLifecycle(
                        mode = Mode.VPN,
                        phase = DeviceRuntimeLifecyclePhase.StartCommand,
                        observedAtMillis = index.toLong(),
                    ),
                )
            }

            val events = store.events.take(DeviceRuntimeEvidenceCapacity).toList()

            assertEquals(3L, events.first().observedAtMillis)
            assertEquals((DeviceRuntimeEvidenceCapacity + 2).toLong(), events.last().observedAtMillis)
            assertEquals(
                events.map(DeviceRuntimeEvidence::observedAtMillis).sorted(),
                events.map { it.observedAtMillis },
            )
        }

    @Test
    fun `data-plane delta is positive-only and identifies forwarding`() {
        val before =
            DeviceRuntimeDataPlaneCounters(
                tunnelTxPackets = 1L,
                tunnelTxBytes = 10L,
                tunnelRxPackets = 2L,
                tunnelRxBytes = 20L,
                nativeTxPackets = 3L,
                nativeTxBytes = 30L,
                nativeRxPackets = 4L,
                nativeRxBytes = 40L,
            )
        val after =
            before.copy(
                tunnelTxPackets = 3L,
                tunnelTxBytes = 31L,
                nativeRxPackets = 1L,
                nativeRxBytes = 1L,
            )

        val delta = after.deltaFrom(before)

        assertEquals(2L, delta.tunnelPackets)
        assertEquals(21L, delta.tunnelBytes)
        assertEquals(0L, delta.nativePackets)
        assertEquals(0L, delta.nativeBytes)
        assertEquals(true, delta.hasForwarding)
    }
}
