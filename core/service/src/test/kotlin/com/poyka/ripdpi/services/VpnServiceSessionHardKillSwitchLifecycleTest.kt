package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class VpnServiceSessionHardKillSwitchLifecycleTest {
    @Test
    fun `create registers refresh before session construction and closes it on failure`() {
        val failure = IllegalStateException("session construction failed")
        val events = mutableListOf<String>()
        val refreshLifecycle = RecordingRefreshLifecycle(events)
        val lifecycle =
            createLifecycle(
                refreshLifecycle = refreshLifecycle,
                sessionComponentBuilderProvider =
                    Provider {
                        events += "session-construction"
                        throw failure
                    },
            )

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                lifecycle.createShellDelegate()
            }

        assertSame(failure, thrown)
        assertEquals(listOf("refresh-start", "session-construction", "refresh-close"), events)
    }

    @Test
    fun `destroy closes refresh lifecycle when no session was constructed`() {
        val events = mutableListOf<String>()
        val refreshLifecycle = RecordingRefreshLifecycle(events)
        val lifecycle =
            createLifecycle(
                refreshLifecycle = refreshLifecycle,
                sessionComponentBuilderProvider = Provider { error("must not construct session") },
            )

        lifecycle.destroy()

        assertEquals(listOf("refresh-close"), events)
    }

    private fun createLifecycle(
        refreshLifecycle: HardKillSwitchRefreshLifecycle,
        sessionComponentBuilderProvider: Provider<VpnServiceSessionComponentBuilder>,
    ): VpnServiceSessionLifecycle {
        val service = Robolectric.buildService(RipDpiVpnService::class.java).get()
        val runtimeResumeIntentTracker = RuntimeResumeIntentTracker()
        return VpnServiceSessionLifecycle(
            service = service,
            serviceStateStore = TestServiceStateStore(),
            sessionComponentBuilderProvider = sessionComponentBuilderProvider,
            activeProtectSocketPathProvider = ActiveProtectSocketPathProvider(),
            runtimeResumeIntentTracker = runtimeResumeIntentTracker,
            acceptedUserStopRecorder =
                AcceptedUserStopRecorder(
                    bootSessionStateStore = NoopBootSessionStateStore,
                    runtimeResumeIntentTracker = runtimeResumeIntentTracker,
                    serviceIntentArbiter = ServiceIntentArbiter(),
                ),
            transportFailoverApplyTracker = TransportFailoverApplyTracker(),
            hardKillSwitchRefreshBroadcastLifecycle = refreshLifecycle,
        )
    }

    private class RecordingRefreshLifecycle(
        private val events: MutableList<String>,
    ) : HardKillSwitchRefreshLifecycle {
        override fun start() {
            events += "refresh-start"
        }

        override fun close() {
            events += "refresh-close"
        }
    }

    private object NoopBootSessionStateStore : BootSessionStateStore {
        override fun lastSession(): BootSessionPointer? = null

        override fun recordSession(
            profileId: String,
            mode: Mode,
        ) = Unit

        override fun clear() = Unit

        override fun wasRunningAtUpdate(): Boolean = false

        override fun setWasRunningAtUpdate(value: Boolean) = Unit
    }
}
