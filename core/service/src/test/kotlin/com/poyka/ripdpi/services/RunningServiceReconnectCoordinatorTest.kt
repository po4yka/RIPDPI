package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningServiceReconnectCoordinatorTest {
    @Test fun `confirmation from replaced runtime or mode cannot stop current service`() =
        runTest {
            val fixture = Fixture()
            fixture.apply("old")
            fixture.store.observeSaved(Mode.VPN, fixture.identities.capture(listOf("new-saved"), listOf("dns")))
            val captured = RunningReconnectRequest.ConfirmedRuntime(Mode.VPN, "old", 1)
            fixture.apply("new-manual-runtime")
            assertEquals(
                RunningReconnectResult.Failed(RunningReconnectFailure.Superseded),
                fixture.coordinator.reconnect(captured),
            )
            assertEquals(0, fixture.dispatch.stops)
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            assertEquals(
                RunningReconnectResult.Failed(RunningReconnectFailure.Superseded),
                fixture.coordinator.reconnect(captured),
            )
            assertEquals(0, fixture.dispatch.stops)
        }

    @Test
    fun `global cancel stops reconnect launched by another entry point`() =
        runTest {
            val fixture = Fixture()
            val externallyLaunched =
                async { fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)) }
            runCurrent()
            val generation = fixture.dispatch.generation
            fixture.coordinator.cancelReconnect()
            runCurrent()
            assertTrue(externallyLaunched.isCancelled)
            assertEquals(null, fixture.arbiter.runIfExplicitUserIntentCurrent(generation) { "late start" })
            assertTrue(fixture.coordinator.reconnectState.value is RunningReconnectState.Cancelled)
            assertTrue(
                fixture.store.applications.value[Mode.VPN] !is RuntimeConfigurationApplication.Applied,
            )
        }

    @Test
    fun `live lockdown policy overrides stale cache and missing live owner fails closed`() =
        runTest {
            val fixture = Fixture()
            fixture.liveLockdown.register(
                fixture,
            ) { AndroidHardKillSwitchSnapshot(AndroidHardKillSwitchStatus.ENABLED) }
            assertEquals(
                RunningReconnectResult.Failed(RunningReconnectFailure.Lockdown),
                fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)),
            )
            assertEquals(0, fixture.dispatch.stops)
            fixture.liveLockdown.unregister(fixture)
            assertEquals(
                RunningReconnectResult.Failed(RunningReconnectFailure.LockdownUnknown),
                fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)),
            )
            assertEquals(0, fixture.dispatch.stops)
            fixture.killSwitch.update(AndroidHardKillSwitchSnapshot(AndroidHardKillSwitchStatus.ENABLED))
            fixture.liveLockdown.register(
                fixture,
            ) { AndroidHardKillSwitchSnapshot(AndroidHardKillSwitchStatus.NOT_ENABLED) }
            val reconnect = async { fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)) }
            runCurrent()
            assertEquals(1, fixture.dispatch.stops)
            fixture.coordinator.cancelReconnect()
            runCurrent()
            assertTrue(reconnect.isCancelled)
        }

    @Test
    fun `later manual ACK clears failed outcome but old confirmed runtime does not`() =
        runTest {
            val fixture = Fixture()
            fixture.coordinator.observeManualRuntimeAcknowledgments(backgroundScope)
            fixture.apply("old")
            fixture.dispatch.preflightResult =
                ServiceStartPreflightResult.Rejected(ServiceStartRejectionReason.VpnConsentMissing)
            fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN))
            runCurrent()
            assertTrue(fixture.coordinator.reconnectState.value is RunningReconnectState.Failed)
            fixture.applyManual("later-manual-runtime")
            runCurrent()
            assertEquals(RunningReconnectState.Idle, fixture.coordinator.reconnectState.value)
        }

    @Test
    fun `failure published by old runtime during stop never rejects accepted replacement`() =
        runTest {
            val fixture = Fixture()
            fixture.apply("old")
            fixture.dispatch.onStop = {
                val old =
                    RuntimeConfigurationAttempt(
                        "old",
                        2,
                        Mode.VPN,
                        RuntimeConfigurationSelection("native"),
                        RuntimeConfigurationApplyReason.PolicyRefresh,
                        originalIntent =
                            com.poyka.ripdpi.data.RuntimeAppliedIntent.Continuation(
                                fixture.original("old").receipt,
                                com.poyka.ripdpi.data
                                    .RuntimeAppliedUseIdentity("old", 1, Mode.VPN.preferenceValue),
                            ),
                        catalogGeneration = 0,
                    )
                assertTrue(
                    runCatching {
                        fixture.store.begin(
                            old,
                            fixture.identities.capture(listOf("old"), listOf("dns")),
                        )
                    }.isFailure,
                )
                assertFalse(fixture.store.fail(old, RuntimeConfigurationApplyFailure.RuntimeRejected))
            }
            val reconnect = async { fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)) }
            runCurrent()
            assertFalse(reconnect.isCompleted)
            fixture.apply("new")
            runCurrent()
            assertEquals(RunningReconnectResult.Applied, reconnect.await())
        }

    @Test
    fun `start timeout invalidates late framework start while preserving later manual start`() =
        runTest {
            val fixture = Fixture()
            assertEquals(
                RunningReconnectResult.Failed(RunningReconnectFailure.StartTimedOut),
                fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)),
            )
            val expired = fixture.dispatch.generation
            assertEquals(null, fixture.arbiter.runIfExplicitUserIntentCurrent(expired) { "late framework start" })
            val manual = fixture.arbiter.userStart({ fixture.arbiter.captureExplicitUserIntentGeneration() }, { true })
            assertFalse(fixture.arbiter.cancelIfCurrent(expired))
            assertEquals(manual, fixture.arbiter.captureExplicitUserIntentGeneration())
        }

    @Test
    fun `old failed attempt cannot complete or reject a newly accepted start`() =
        runTest {
            val fixture = Fixture()
            fixture.fail("old")
            val reconnect = async { fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)) }
            runCurrent()
            assertFalse(reconnect.isCompleted)
            assertEquals(1, fixture.dispatch.starts)
            fixture.apply("new")
            runCurrent()
            assertEquals(RunningReconnectResult.Applied, reconnect.await())
        }

    @Test
    fun `new runtime failure is fenced separately from previous failed state`() =
        runTest {
            val fixture = Fixture()
            fixture.fail("old")
            val reconnect = async { fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)) }
            runCurrent()
            fixture.fail("new")
            runCurrent()
            assertEquals(RunningReconnectResult.Failed(RunningReconnectFailure.RuntimeFailed), reconnect.await())
        }

    @Test
    fun `cancelling after accepted dispatch invalidates queued start without overriding later user intent`() =
        runTest {
            val fixture = Fixture()
            val reconnect = async { fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)) }
            runCurrent()
            val acceptedGeneration = fixture.dispatch.generation
            reconnect.cancel()
            runCurrent()
            assertEquals(null, fixture.arbiter.runIfExplicitUserIntentCurrent(acceptedGeneration) { "queued start" })
            assertTrue(fixture.coordinator.reconnectState.value is RunningReconnectState.Cancelled)
            val later = fixture.arbiter.userStart({ fixture.arbiter.captureExplicitUserIntentGeneration() }, { true })
            assertFalse(fixture.arbiter.cancelIfCurrent(acceptedGeneration))
            assertEquals(later, fixture.arbiter.captureExplicitUserIntentGeneration())
        }

    @Test
    fun `permission and lockdown preflights leave current runtime running`() =
        runTest {
            val fixture = Fixture()
            fixture.dispatch.preflightResult =
                ServiceStartPreflightResult.Rejected(ServiceStartRejectionReason.VpnConsentMissing)
            assertEquals(
                RunningReconnectResult.Failed(RunningReconnectFailure.PermissionRequired),
                fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)),
            )
            assertEquals(0, fixture.dispatch.stops)
            fixture.dispatch.preflightResult = ServiceStartPreflightResult.Allowed
            fixture.killSwitch.update(AndroidHardKillSwitchSnapshot(AndroidHardKillSwitchStatus.ENABLED))
            assertEquals(
                RunningReconnectResult.Failed(RunningReconnectFailure.Lockdown),
                fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)),
            )
            assertEquals(0, fixture.dispatch.stops)
            assertEquals(AppStatus.Running, fixture.service.status.value.first)
        }

    @Test
    fun `stop timeout never dispatches replacement`() =
        runTest {
            val fixture = Fixture()
            fixture.dispatch.haltOnStop = false
            assertEquals(
                RunningReconnectResult.Failed(RunningReconnectFailure.StopTimedOut),
                fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)),
            )
            assertEquals(0, fixture.dispatch.starts)
        }

    @Test
    fun `manual stop supersedes in flight reconnect`() =
        runTest {
            val fixture = Fixture()
            val reconnect = async { fixture.coordinator.reconnect(RunningReconnectRequest.CurrentSaved(Mode.VPN)) }
            runCurrent()
            fixture.arbiter.userStop { }
            runCurrent()
            assertEquals(RunningReconnectResult.Failed(RunningReconnectFailure.Superseded), reconnect.await())
        }

    private class Fixture {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val arbiter = ServiceIntentArbiter(authority)
        val service = TestServiceStateStore(AppStatus.Running to Mode.VPN)
        val store =
            AppliedRuntimeConfigurationStore(
                testRuntimeAppliedReceiptConsumer(
                    authority,
                ),
            )
        val identities = RuntimeConfigurationIdentityFactory()
        val killSwitch =
            object : AndroidHardKillSwitchStateStore {
                override val snapshot =
                    MutableStateFlow(AndroidHardKillSwitchSnapshot(AndroidHardKillSwitchStatus.NOT_ENABLED))

                override fun update(snapshot: AndroidHardKillSwitchSnapshot) {
                    this.snapshot.value = snapshot
                }
            }
        val dispatch = Dispatch(service)
        val liveLockdown = LiveVpnLockdownReader().apply { register(this@Fixture) { killSwitch.snapshot.value } }
        val coordinator =
            RunningServiceReconnectCoordinator(
                dispatch,
                arbiter,
                service,
                store,
                liveLockdown,
                object : TestSynchronousServiceController() {
                    override fun recordStart(
                        mode: Mode,
                        receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt?,
                    ) = (
                        receipt?.let(ServiceStartResult::Accepted)
                            ?: ServiceStartResult.MaintenanceAccepted(mode)
                    )

                    override fun recordStop() = Unit

                    override suspend fun prepareStart(mode: Mode) = authority.reserveStart(mode)

                    override suspend fun prepareStop() = authority.reserveStop()

                    override suspend fun prepareStopIfCurrent(
                        expected: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
                    ) = authority.reserveStopIfCurrent(expected)

                    override suspend fun captureRuntimeSnapshot() = authority.snapshotAuthority()
                },
            )

        fun fail(runtime: String) {
            val attempt = attempt(runtime)
            assertTrue(store.begin(attempt, identities.capture(listOf(runtime), listOf("dns"))))
            assertTrue(store.fail(attempt, RuntimeConfigurationApplyFailure.RuntimeRejected))
        }

        fun apply(runtime: String) {
            val attempt = attempt(runtime)
            assertTrue(store.begin(attempt, identities.capture(listOf(runtime), listOf("dns"))))
            assertTrue(
                store.acknowledge(
                    attempt,
                    AppliedRuntimeConfiguration(
                        runtime,
                        1,
                        100,
                        Mode.VPN,
                        attempt.requestedSelection,
                        attempt.requestedSelection,
                        RuntimeConfigurationDns("plain", "system"),
                        RuntimeConfigurationStrategy(false),
                        attempt.reason,
                    ),
                    null,
                ),
            )
        }

        private val originals = mutableMapOf<String, com.poyka.ripdpi.data.RuntimeAppliedIntent>()

        fun original(runtime: String): com.poyka.ripdpi.data.RuntimeAppliedIntent =
            originals.getOrPut(runtime) {
                com.poyka.ripdpi.data.RuntimeAppliedIntent
                    .Activation(dispatch.receipt ?: authority.reserveStart(Mode.VPN))
            }

        fun applyManual(runtime: String) {
            val receipt = authority.reserveStart(Mode.VPN)
            checkNotNull(arbiter.dispatchExplicit(receipt))
            originals[runtime] =
                com.poyka.ripdpi.data.RuntimeAppliedIntent
                    .Activation(receipt)
            apply(runtime)
        }

        private fun attempt(runtime: String) =
            RuntimeConfigurationAttempt(
                runtime,
                1,
                Mode.VPN,
                RuntimeConfigurationSelection("native"),
                RuntimeConfigurationApplyReason.UserReconnect,
                originalIntent = original(runtime),
                catalogGeneration = 0,
            )
    }

    private class Dispatch(
        private val service: TestServiceStateStore,
    ) : RunningReconnectDispatch {
        var preflightResult: ServiceStartPreflightResult = ServiceStartPreflightResult.Allowed
        var onStop: () -> Unit = {}
        var haltOnStop = true
        var stops = 0
        var starts = 0
        var generation = -1L
        var receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt? = null

        override fun preflight(mode: Mode) = preflightResult

        override fun stopIfCurrent(lease: ServiceDispatchLease): Boolean {
            stops += 1
            onStop()
            if (haltOnStop) service.setStatus(AppStatus.Halted, Mode.VPN)
            return true
        }

        override fun startIfCurrent(
            mode: Mode,
            lease: ServiceDispatchLease,
        ): ServiceStartResult {
            starts += 1
            this.generation = lease.processGeneration
            val captured = lease.durable as com.poyka.ripdpi.data.RuntimeActivationReceipt
            receipt = captured
            return ServiceStartResult.Accepted(captured)
        }
    }
}
