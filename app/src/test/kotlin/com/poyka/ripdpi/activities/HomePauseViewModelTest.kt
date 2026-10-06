package com.poyka.ripdpi.activities

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DesiredRuntimeState
import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityPersistence
import com.poyka.ripdpi.data.PauseAuthorityState
import com.poyka.ripdpi.data.PauseClock
import com.poyka.ripdpi.data.PauseClockReading
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationPreparation
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.RuntimeIntentLinearizer
import com.poyka.ripdpi.services.LiveVpnLockdownReader
import com.poyka.ripdpi.services.ServiceIntentArbiter
import com.poyka.ripdpi.services.TimedPauseController
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomePauseViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun failedStopCommitKeepsPauseAndReportsActionableFailure() =
        runTest {
            val fixture = Fixture()
            val before = fixture.persistence.state
            fixture.persistence.failure = IllegalStateException("checked commit rejected")
            val store = ViewModelStore().apply { put("pause", fixture.viewModel) }
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.viewModel.uiState.collect {}
                }
            try {
                fixture.viewModel.stop().join()
                runCurrent()
                val result = fixture.viewModel.uiState.value
                assertTrue(result.requestFailed)
                assertEquals(PausePhase.Paused, result.phase)
                assertEquals(before, fixture.persistence.state)
                assertEquals(before, fixture.authority.states.value)
            } finally {
                collector.cancel()
                store.clear()
            }
        }

    @Test
    fun failedResumeDeferralKeepsOriginalPauseAndReportsFailure() =
        runTest {
            val fixture = Fixture(denyForeground = true)
            val before = fixture.persistence.state
            fixture.persistence.failure = IllegalStateException("checked commit rejected")
            val store = ViewModelStore().apply { put("pause", fixture.viewModel) }
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.viewModel.uiState.collect {}
                }
            try {
                fixture.viewModel.resume().join()
                runCurrent()
                val result = fixture.viewModel.uiState.value
                assertTrue(result.requestFailed)
                assertEquals(1, fixture.foregroundAttempts)
                assertEquals(PausePhase.Paused, result.phase)
                assertEquals(before, fixture.persistence.state)
                assertEquals(before, fixture.authority.states.value)
            } finally {
                collector.cancel()
                store.clear()
            }
        }

    @Test
    fun clockArithmeticFailureIsReportedWithoutChangingPause() =
        runTest {
            val fixture = Fixture(initialGeneration = Long.MAX_VALUE - 1)
            val before = fixture.persistence.state
            val store = ViewModelStore().apply { put("pause", fixture.viewModel) }
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.viewModel.uiState.collect {}
                }
            try {
                fixture.viewModel.stop().join()
                runCurrent()
                assertTrue(fixture.viewModel.uiState.value.requestFailed)
                assertEquals(before, fixture.persistence.state)
                assertEquals(before, fixture.authority.states.value)
            } finally {
                collector.cancel()
                store.clear()
            }
        }

    @Test
    fun cancelledCheckedCommandKeepsPauseAndRemainsCancellation() =
        runTest {
            val fixture = Fixture(cancelRecovery = true)
            val before = fixture.persistence.state
            val store = ViewModelStore().apply { put("pause", fixture.viewModel) }
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.viewModel.uiState.collect {}
                }
            try {
                val command = fixture.viewModel.stop()
                command.join()
                runCurrent()
                assertTrue(command.isCancelled)
                assertFalse(fixture.viewModel.uiState.value.requestFailed)
                assertEquals(before, fixture.persistence.state)
                assertEquals(before, fixture.authority.states.value)
            } finally {
                collector.cancel()
                store.clear()
            }
        }

    @Test
    fun matchingCleanupOwnershipOverridesResumingAndDisablesResumePresentation() {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val pending = authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority())
        check(authority.transition(pending, PausePhase.Paused, null))
        checkNotNull(authority.claimResume(pending, immediate = true))
        val resuming = authority.states.value
        val cleanup = pending.copy(phase = PausePhase.CleanupPending)

        assertSame(cleanup, presentedPauseIntent(resuming, cleanup))
        assertEquals(PausePhase.CleanupPending, presentedPauseIntent(resuming, cleanup)?.phase)
    }

    @Test
    fun staleCleanupOwnershipCannotHideNewerPause() {
        val authority =
            com.poyka.ripdpi.data
                .testPauseAuthority()
        val old = authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority())
        authority.reserveStop()
        val newer = authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority())
        val staleCleanup = old.copy(phase = PausePhase.CleanupPending)

        assertSame(newer, presentedPauseIntent(authority.states.value, staleCleanup))
    }

    private class Fixture(
        denyForeground: Boolean = false,
        cancelRecovery: Boolean = false,
        initialGeneration: Long? = null,
    ) {
        val persistence = FaultPersistence(initialGeneration)
        val authority =
            PauseIntentAuthority(
                persistence,
                object : PauseClock {
                    override fun read() = PauseClockReading(1_800_000_000_000L, 10_000L, 7)
                },
                RuntimeIntentLinearizer(),
            ).apply {
                initializeAfterMigration()
                val pending = begin(Mode.Proxy, 300_000, snapshotAuthority())
                check(transition(pending, PausePhase.Paused, null))
            }

        @Volatile var foregroundAttempts = 0
        private val context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun startForegroundService(service: Intent): ComponentName? {
                    foregroundAttempts += 1
                    check(!denyForeground) { "foreground start denied" }
                    return super.startForegroundService(service)
                }
            }
        private val recovery =
            object : ProfileMutationRecoveryAccess {
                override suspend fun recover() = Unit

                override suspend fun <T> readRecovered(block: suspend () -> T): T {
                    if (cancelRecovery) throw CancellationException("cancelled command")
                    return block()
                }

                override suspend fun captureMutation(origin: ProfileMutationOrigin) =
                    ProfileMutationPreparation(origin, authority.reference())

                override suspend fun <T> mutateCatalog(
                    preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
                    block: suspend () -> T,
                ): T {
                    check(commitMutationIntent(preparation) != com.poyka.ripdpi.data.ProfileMutationOutcome.Superseded)
                    return block()
                }

                override suspend fun <T> mutateReservedCatalog(
                    receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
                    block: suspend () -> T,
                ): T = block()

                override suspend fun activateSelector(
                    preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
                    groupId: String,
                    memberId: String,
                    choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
                ): com.poyka.ripdpi.data.ProfileMutationOutcome {
                    val outcome = commitMutationIntent(preparation)
                    val receipt =
                        (outcome as? com.poyka.ripdpi.data.ProfileMutationOutcome.Reserved)?.receipt
                            as? com.poyka.ripdpi.data.ProfileActivationReceipt
                    if (receipt != null) {
                        choice.commitMember(
                            groupId,
                            memberId,
                            com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                                .Manual(receipt),
                        )
                    }
                    return outcome
                }

                override suspend fun commitMutationIntent(preparation: ProfileMutationPreparation) =
                    authority.invalidateForMutation(
                        preparation.origin,
                        UUID.randomUUID().toString(),
                        preparation.expectedPauseAuthority,
                    )

                override suspend fun runReset(block: suspend (DurableCommandReceipt) -> Unit): DurableCommandReceipt {
                    val receipt = authority.reserveStop()
                    block(receipt)
                    return receipt
                }
            }
        private val stateStore = FakeServiceStateStore(AppStatus.Halted to Mode.Proxy)
        val viewModel =
            HomePauseViewModel(
                TimedPauseController(
                    context,
                    authority,
                    recovery,
                    stateStore,
                    LiveVpnLockdownReader(),
                    ServiceIntentArbiter(authority),
                ),
                authority,
                stateStore,
            )
    }

    private class FaultPersistence(
        initialGeneration: Long?,
    ) : PauseAuthorityPersistence {
        @Volatile var state: PauseAuthorityState? =
            initialGeneration?.let {
                PauseAuthorityState(
                    it,
                    null,
                    null,
                    desired = DesiredRuntimeState.LegacyUnknown,
                    profileUtility =
                        com.poyka.ripdpi.data.ProfileUtilityState
                            .empty(),
                )
            }

        @Volatile var failure: RuntimeException? = null

        override fun read() = state

        override fun commit(state: PauseAuthorityState) {
            failure?.let { throw it }
            this.state = state
        }
    }
}
