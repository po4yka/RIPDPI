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
}
