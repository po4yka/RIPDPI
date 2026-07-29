package com.poyka.ripdpi.services

import android.content.ComponentCallbacks2
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.DeviceRuntimeEvidenceStore
import com.poyka.ripdpi.data.DeviceRuntimeForegroundCallKind
import com.poyka.ripdpi.data.DeviceRuntimeForegroundOutcome
import com.poyka.ripdpi.data.DeviceRuntimeForegroundServiceState
import com.poyka.ripdpi.data.DeviceRuntimeForegroundServiceType
import com.poyka.ripdpi.data.DeviceRuntimeLifecyclePhase
import com.poyka.ripdpi.data.DeviceRuntimeMemoryPressure
import com.poyka.ripdpi.data.DeviceRuntimeValue
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class AndroidRuntimeEvidenceReporterTest {
    @Test
    fun `records lifecycle and successful foreground return for both modes`() {
        val store = RecordingEvidenceStore()
        val reporter = reporter(store)

        reporter.recordLifecycle(Mode.VPN, DeviceRuntimeLifecyclePhase.Created)
        reporter.runForegroundCall(
            Mode.VPN,
            DeviceRuntimeForegroundCallKind.Initial,
            DeviceRuntimeForegroundServiceType.SpecialUse,
        ) {}
        reporter.recordLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.StartCommand)
        reporter.runForegroundCall(
            Mode.Proxy,
            DeviceRuntimeForegroundCallKind.Initial,
            DeviceRuntimeForegroundServiceType.SpecialUse,
        ) {}

        assertEquals(
            listOf(Mode.VPN, Mode.Proxy),
            store.eventsList.filterIsInstance<DeviceRuntimeEvidence.ForegroundCall>().map { it.mode },
        )
        assertEquals(
            listOf(DeviceRuntimeForegroundOutcome.Returned, DeviceRuntimeForegroundOutcome.Returned),
            store.eventsList.filterIsInstance<DeviceRuntimeEvidence.ForegroundCall>().map { it.outcome },
        )
    }

    @Test
    fun `classifies foreground failure without retaining hostile message and rethrows same instance`() {
        val store = RecordingEvidenceStore()
        val reporter = reporter(store)
        val failure = SecurityException("serial=secret ssid=private host=bad.example")

        val caught =
            runCatching {
                reporter.runForegroundCall(
                    Mode.VPN,
                    DeviceRuntimeForegroundCallKind.Initial,
                    DeviceRuntimeForegroundServiceType.SpecialUse,
                ) { throw failure }
            }.exceptionOrNull()

        assertSame(failure, caught)
        val event = store.eventsList.single() as DeviceRuntimeEvidence.ForegroundCall
        assertEquals(DeviceRuntimeForegroundOutcome.SecurityRejected, event.outcome)
        assertFalse(event.toString().contains("secret"))
        assertFalse(event.toString().contains("bad.example"))
    }

    @Test
    fun `deduplicates unchanged vpn policy and keeps categorical values`() {
        val store = RecordingEvidenceStore()
        val reporter = reporter(store)
        val snapshot = AndroidHardKillSwitchStateReader.fromPlatformFlags(alwaysOn = true, lockdown = true)

        reporter.recordVpnPolicy(snapshot)
        reporter.recordVpnPolicy(snapshot.copy(updatedAtMillis = snapshot.updatedAtMillis + 1))

        val event = store.eventsList.single() as DeviceRuntimeEvidence.VpnPolicy
        assertEquals(DeviceRuntimeValue.Enabled, event.alwaysOn)
        assertEquals(DeviceRuntimeValue.Enabled, event.lockdown)
    }

    @Test
    fun `maps actual trim callbacks to coarse pressure bands`() {
        assertEquals(
            DeviceRuntimeMemoryPressure.UiHidden,
            classifyMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN),
        )
        assertEquals(
            DeviceRuntimeMemoryPressure.Background,
            classifyMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND),
        )
        assertEquals(DeviceRuntimeMemoryPressure.Unknown, classifyMemoryPressure(Int.MAX_VALUE))
    }

    private fun reporter(store: RecordingEvidenceStore) =
        AndroidRuntimeEvidenceReporter(store, AndroidRuntimeEvidenceClock { 1_000L })
}

private class RecordingEvidenceStore : DeviceRuntimeEvidenceStore {
    val eventsList = mutableListOf<DeviceRuntimeEvidence>()
    override val events: Flow<DeviceRuntimeEvidence> = emptyFlow()
    override val foregroundServiceState: StateFlow<DeviceRuntimeForegroundServiceState> =
        MutableStateFlow(DeviceRuntimeForegroundServiceState())

    override fun record(event: DeviceRuntimeEvidence) {
        eventsList += event
    }
}
