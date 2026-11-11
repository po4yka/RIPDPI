package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.testPauseAuthority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WarpProbePauseRaceTest {
    @Test
    fun `pause superseded while waiting for lifecycle lock keeps probe admission open`() =
        runTest {
            val env = with(VpnServiceRuntimeCoordinatorTest()) { newEnv() }
            val protection = ActiveProtectSocketPathProvider()
            val owner = protection.set("/tmp/current-vpn-protection.sock") { true }
            val authority = testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val resume = RuntimeResumeIntentTracker(authority)
            val lifecycle =
                VpnServiceSessionLifecycle(
                    service = Robolectric.buildService(RipDpiVpnService::class.java).get(),
                    sessionComponentBuilderProvider = Provider { error("session must not be constructed") },
                    activeProtectSocketPathProvider = protection,
                    runtimeResumeIntentTracker = resume,
                    serviceIntentArbiter = arbiter,
                    acceptedUserStopRecorder =
                        AcceptedUserStopRecorder(
                            profileRecovery =
                                com.poyka.ripdpi.data
                                    .testProfileRecovery(),
                            pauseAuthority = authority,
                            bootSessionStateStore = NoopBootSessionStateStore,
                            runtimeResumeIntentTracker = resume,
                            serviceIntentArbiter = arbiter,
                        ),
                    transportFailoverApplyTracker = TransportFailoverApplyTracker(),
                    serviceStopProvenanceRecorder = ServiceStopProvenanceRecorder { _, _ -> },
                )
            ReflectionHelpers.setField(lifecycle, "coordinator", env.coordinator)
            ReflectionHelpers.setField(lifecycle, "protectPathLease", owner)
            val mutex = ReflectionHelpers.getField<Mutex>(env.coordinator, "mutex")
            var current = true
            protection.captureWarpProbe().use { captured ->
                mutex.lock()
                val release = async { lifecycle.releaseRetainingShell(RuntimeStopGuard(isCurrent = { current })) }
                runCurrent()
                current = false
                mutex.unlock()

                assertEquals(RuntimeStopOutcome.Superseded, release.await())
                assertEquals("/tmp/current-vpn-protection.sock", protection.current())
                assertTrue(captured.controller.protect(1))
                protection.captureWarpProbe().use { assertTrue(it.controller.protect(2)) }
            }
            protection.clear(owner)
        }

    private object NoopBootSessionStateStore : com.poyka.ripdpi.data.boot.BootSessionStateStore {
        override fun lastSession(): com.poyka.ripdpi.data.boot.BootSessionPointer? = null

        override fun recordSession(
            profileId: String,
            mode: com.poyka.ripdpi.data.Mode,
        ) = Unit

        override fun clear() = Unit

        override fun wasRunningAtUpdate(): Boolean = false

        override fun setWasRunningAtUpdate(value: Boolean) = Unit
    }
}
