package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellExplicitStartTest {
    @Test
    fun `explicit profile command bypasses simple reset and preserves target`() =
        runTest {
            val fixture = Fixture(this)
            fixture.publishAwg()
            runCurrent()
            assertEquals("awg-newer", fixture.selectedProfile)
            assertEquals(listOf("standalone-start"), fixture.operations)
            assertEquals(1, fixture.acceptedStarts)
        }

    @Test
    fun `queued ordinary start cannot clear a newer standalone selection`() =
        runTest {
            val releaseQueue = CompletableDeferred<Unit>()
            val fixture = Fixture(this, diagnostics = { releaseQueue.await() })
            fixture.delegate.onStartCommand(diagnosticsStartAction, 1)
            runCurrent()
            fixture.deliverStart(fixture.acceptStart())
            fixture.publishAwg()
            releaseQueue.complete(Unit)
            runCurrent()
            assertEquals("awg-newer", fixture.selectedProfile)
            assertEquals(listOf("standalone-start"), fixture.operations)
        }

    @Test
    fun `suspended ordinary preparation cannot clear or start over newer profile`() =
        runTest {
            val releasePrepare = CompletableDeferred<Unit>()
            val fixture = Fixture(this, prepare = { releasePrepare.await() })
            fixture.deliverStart(fixture.acceptStart())
            runCurrent()
            fixture.publishAwg()
            releasePrepare.complete(Unit)
            runCurrent()
            assertEquals("awg-newer", fixture.selectedProfile)
            assertEquals(listOf("standalone-start"), fixture.operations)
        }

    @Test
    fun `delayed old command is rejected before recording accepted start`() =
        runTest {
            val fixture = Fixture(this)
            val oldGeneration = fixture.acceptStart()
            fixture.publishAwg()
            fixture.deliverStart(oldGeneration)
            runCurrent()
            assertEquals("awg-newer", fixture.selectedProfile)
            assertEquals(1, fixture.acceptedStarts)
            assertEquals(listOf("standalone-start"), fixture.operations)
        }

    @Test
    fun `newer ordinary user start retakes standalone authority`() =
        runTest {
            val fixture = Fixture(this)
            fixture.publishAwg()
            runCurrent()
            fixture.deliverStart(fixture.acceptStart())
            runCurrent()
            assertNull(fixture.selectedProfile)
            assertEquals(listOf("standalone-start", "ordinary-start"), fixture.operations)
        }

    @Test
    fun `unstamped explicit command cannot mutate selection`() =
        runTest {
            val fixture = Fixture(this)
            fixture.selectedProfile = "awg-existing"
            fixture.delegate.onStartCommand(startAction, 1)
            runCurrent()
            assertEquals("awg-existing", fixture.selectedProfile)
            assertEquals(0, fixture.acceptedStarts)
            assertEquals(emptyList<String>(), fixture.operations)
        }

    @Test
    fun `delayed dispatched stop cannot invalidate a newer start`() =
        runTest {
            val fixture = Fixture(this)
            fixture.arbiter.userStop { }
            val stopGeneration = fixture.arbiter.captureExplicitUserIntentGeneration()
            val startGeneration = fixture.acceptStart()
            fixture.delegate.onStartCommand(stopAction, 1, explicitUserIntentGeneration = stopGeneration)
            fixture.deliverStart(startGeneration)
            runCurrent()
            assertEquals(startGeneration, fixture.arbiter.captureExplicitUserIntentGeneration())
            assertEquals(listOf("ordinary-start"), fixture.operations)
        }

    @Test
    fun `queued notification stop cannot stop a newer accepted start`() =
        runTest {
            val fixture = Fixture(this)
            fixture.delegate.onStartCommand(notificationStopAction, 1)
            fixture.deliverStart(fixture.acceptStart())
            runCurrent()
            assertEquals(listOf("ordinary-start"), fixture.operations)
        }

    @Test
    fun `accepted dispatched stop records without advancing generation twice`() =
        runTest {
            val fixture = Fixture(this)
            fixture.arbiter.userStop { }
            val generation = fixture.arbiter.captureExplicitUserIntentGeneration()
            fixture.delegate.onStartCommand(stopAction, 1, explicitUserIntentGeneration = generation)
            runCurrent()
            assertEquals(generation, fixture.arbiter.captureExplicitUserIntentGeneration())
            assertEquals(listOf("stop"), fixture.operations)
        }

    private class Fixture(
        scope: TestScope,
        diagnostics: suspend () -> Unit = {},
        prepare: suspend () -> Unit = {},
    ) {
        val arbiter = ServiceIntentArbiter()
        val operations = mutableListOf<String>()
        var selectedProfile: String? = null
        var acceptedStarts = 0
        private val recorder =
            AcceptedUserStopRecorder(InMemoryBootSessionStateStore(), RuntimeResumeIntentTracker(), arbiter)
        val delegate =
            ServiceShellDelegate(
                serviceScope = scope.backgroundScope,
                serviceIntentArbiter = arbiter,
                serviceLabel = "vpn",
                onStart = { operations += "ordinary-start" },
                onStartWithId = { action, _ ->
                    if (action == diagnosticsStartAction) diagnostics() else operations += "ordinary-start"
                },
                onStop = { _, _ -> operations += "stop" },
                beforeUserStart = { guard ->
                    prepare()
                    guard.runIfCurrent { selectedProfile = null }
                },
                intentCallbacks =
                    ServiceShellIntentCallbacks(
                        acceptedStart = { acceptedStarts++ },
                        acceptedStop = recorder::record,
                    ),
                transportFailoverCommandHandler =
                    TransportFailoverCommandHandler(
                        restart = { _, _ -> error("Unexpected automatic failover") },
                        activate = { id, target ->
                            assertEquals(42L, id)
                            assertEquals(TransportFailoverTarget(TransportKindAmneziaWg, "awg-newer"), target)
                            operations += "standalone-start"
                        },
                    ),
                ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            )

        fun acceptStart(): Long = arbiter.userStart(arbiter::captureExplicitUserIntentGeneration) { true }

        fun deliverStart(generation: Long) {
            delegate.onStartCommand(startAction, 2, explicitUserIntentGeneration = generation)
        }

        fun publishAwg() {
            arbiter.serialize {
                selectedProfile = "awg-newer"
                delegate.onStartCommand(
                    transportActivationStartAction,
                    3,
                    42L,
                    TransportFailoverTarget(TransportKindAmneziaWg, "awg-newer"),
                    explicitUserIntentGeneration = acceptStart(),
                )
            }
        }
    }
}
