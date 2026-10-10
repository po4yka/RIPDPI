package com.poyka.ripdpi.ui.screens.profiles

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.CheckedPauseAuthorityPersistence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PauseClock
import com.poyka.ripdpi.data.PauseClockReading
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PendingProfileMutation
import com.poyka.ripdpi.data.ProfileMutationGenerationPublisher
import com.poyka.ripdpi.data.ProfileMutationJournal
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProfileMutationStores
import com.poyka.ripdpi.data.ProfileUtilityCatalogReader
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.ProxyGroupBlobStore
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeCommandOrigin
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.RuntimeIntentLinearizer
import com.poyka.ripdpi.data.SharedPreferencesProxyGroupRepository
import com.poyka.ripdpi.data.SharedPreferencesRelayProfileStore
import com.poyka.ripdpi.data.SharedPreferencesWarpEndpointStore
import com.poyka.ripdpi.data.SharedPreferencesWarpProfileStore
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.boot.SharedPreferencesBootSessionStateStore
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SharedPreferencesSelectorSelectionStore
import com.poyka.ripdpi.data.xray.DefaultDurableXrayProfileStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProviderSelectionStore
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.CandidateHttpPayloadProbe
import com.poyka.ripdpi.services.CandidateRelayMeasurement
import com.poyka.ripdpi.services.CandidateRelayMeasurements
import com.poyka.ripdpi.services.CandidateRelayNetworkEpoch
import com.poyka.ripdpi.services.CandidateRelayProbeEnvironment
import com.poyka.ripdpi.services.ControlledRelayRuntime
import com.poyka.ripdpi.services.DefaultServiceRuntimeRegistry
import com.poyka.ripdpi.services.MeasuredActivationRegistry
import com.poyka.ripdpi.services.MeasuredProfileActivationCoordinator
import com.poyka.ripdpi.services.ProfileUtilityProbeFixtures
import com.poyka.ripdpi.services.ProxySessionSecretResolver
import com.poyka.ripdpi.services.RelayProbeEndpoint
import com.poyka.ripdpi.services.RuntimeExperimentSelection
import com.poyka.ripdpi.services.RuntimeExperimentSelectionProvider
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartPreflightResult
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicyCompileResult
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicyCompiler
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Controlled JNI/HTTP unit ports only: these tests make no native readiness or device claim. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProfileUtilityViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `check measures and cleans candidate without dispatch selection or recents`() =
        runTest {
            val f = fixture()
            try {
                val before = f.authority.states.value
                val settings = f.settings.snapshot()
                f.vm.check(Reference)
                val item = f.awaitItem { it.measurement is ProfileMeasurementUiState.Measured }
                assertTrue((item.measurement as ProfileMeasurementUiState.Measured).latencyMillis >= 0)
                assertFalse(item.applied)
                assertNull(item.recentSequence)
                assertEquals(before, f.authority.states.value)
                assertEquals(settings, f.settings.snapshot())
                f.assertNoSelection()
                f.assertCleanedCandidates(1)
                assertEquals(listOf(ProbeUrl), f.httpUrls.toList())
            } finally {
                f.close()
            }
        }

    @Test
    fun `check and select retains original receipt and records recents only after checked consumed input ACK`() =
        runTest {
            val f = fixture()
            try {
                f.vm.checkAndSelect(Reference)
                f.awaitItem { it.measurement is ProfileMeasurementUiState.Measured }
                val receipt = f.dispatches.single()
                val original = checkNotNull(f.authority.states.value).command
                assertEquals(receipt.commandId, original?.commandId)
                assertTrue(receipt.origin is RuntimeCommandOrigin.MeasuredActivation)
                assertEquals(Profile.id, f.settings.snapshot().relayProfileId)
                assertTrue(f.recents().isEmpty())
                f.assertCleanedCandidates(1)

                val attempt = f.begin(receipt)
                assertSame(receipt, attempt.originalIntent.receipt)
                assertTrue(f.recents().isEmpty())
                val consumed = f.consumedInput()
                assertTrue(f.acknowledge(attempt, consumed))
                val applied = f.awaitItem { it.applied && it.recentSequence != null }
                assertEquals(listOf(Reference), f.recents().map { it.reference })
                assertEquals(AppliedAt, applied.lastUsedAtMillis)
                assertEquals(receipt.commandId, checkNotNull(f.authority.states.value).command?.commandId)
                val committed = f.authority.states.value
                assertTrue(f.acknowledge(attempt, consumed))
                assertEquals(committed, f.authority.states.value)
                f.assertCleanedCandidates(2)
            } finally {
                f.close()
            }
        }

    @Test
    fun `different actual consumed input rejects ACK and leaves recents unapplied`() =
        runTest {
            val f = fixture()
            try {
                f.vm.checkAndSelect(Reference)
                f.awaitItem { it.measurement is ProfileMeasurementUiState.Measured }
                val attempt = f.begin(f.dispatches.single())
                val before = f.authority.states.value
                val different = f.consumedInput(Profile.copy(server = "different.example"))
                assertFalse(f.acknowledge(attempt, different))
                assertFalse(f.acknowledge(attempt, null))
                assertEquals(before, f.authority.states.value)
                assertTrue(f.recents().isEmpty())
                assertTrue(f.applied.applications.value[Mode.Proxy] is RuntimeConfigurationApplication.Applying)
                assertFalse(
                    f.vm.uiState.value.profiles
                        .single()
                        .applied,
                )
                f.assertCleanedCandidates(2)
            } finally {
                f.close()
            }
        }

    @Test
    fun `favorite survives fresh authority recovery catalog and ViewModel recreation`() =
        runTest {
            val f = fixture()
            try {
                f.vm.favorite(Reference, f.vm.uiState.value.catalogGeneration, true)
                f.awaitItem { it.favorite }
                val recreated = fixture(f.disk, f.settings, f.credentials)
                try {
                    assertTrue(recreated.awaitItem { it.favorite }.favorite)
                    assertEquals(
                        setOf(Reference),
                        checkNotNull(recreated.authority.states.value).profileUtility.favorites,
                    )
                    recreated.vm.favorite(Reference, recreated.vm.uiState.value.catalogGeneration, false)
                    recreated.awaitItem { !it.favorite }
                    assertTrue(recreated.recents().isEmpty())
                } finally {
                    recreated.close()
                }
            } finally {
                f.close()
            }
        }

    @Test
    fun `failed checked preference commit exposes persistence failure without optimistic favorite`() =
        runTest {
            val f = fixture()
            try {
                val before = f.authority.states.value
                val stored = f.disk.authorityState()
                f.disk.failAuthorityCommit = true
                f.vm.favorite(Reference, f.vm.uiState.value.catalogGeneration, true)
                f.awaitState { it.failure == ProfileUtilityFailure.Persistence }
                assertFalse(
                    f.vm.uiState.value.profiles
                        .single()
                        .favorite,
                )
                assertEquals(before, f.authority.states.value)
                assertEquals(stored, f.disk.authorityState())
                f.assertNoSelection()
                f.disk.failAuthorityCommit = false
                f.vm.favorite(Reference, f.vm.uiState.value.catalogGeneration, true)
                f.awaitItem { it.favorite }
            } finally {
                f.close()
            }
        }

    @Test
    fun `failed measured reservation preference commit rejects selection after candidate cleanup`() =
        runTest {
            val f = fixture()
            try {
                val before = f.authority.states.value
                val settings = f.settings.snapshot()
                f.afterHttp = { f.disk.failAuthorityCommit = true }
                f.vm.checkAndSelect(Reference)
                f.awaitState { it.failure == ProfileUtilityFailure.Persistence }
                assertEquals(before, f.authority.states.value)
                assertEquals(settings, f.settings.snapshot())
                f.assertNoSelection()
                f.assertCleanedCandidates(1)
            } finally {
                f.close()
            }
        }

    @Test
    fun `cleanup failure retains owned runtime rejects selection and retry releases it`() =
        runTest {
            val f = fixture()
            try {
                f.failStop = true
                f.vm.checkAndSelect(Reference)
                f.awaitItem { it.measurement == ProfileMeasurementUiState.Failed(ProfileUtilityFailure.CleanupPending) }
                f.awaitState { it.cleanupPending }
                assertFalse(
                    f.runtimes
                        .single()
                        .finished.isCompleted,
                )
                f.assertNoSelection()
                f.vm.checkAndSelect(Reference)
                withContext(Dispatchers.Default) {
                    withTimeout(10_000) {
                        f.runtimes
                            .single()
                            .failedStops
                            .first { it >= 2 }
                    }
                }
                f.awaitItem { it.measurement == ProfileMeasurementUiState.Failed(ProfileUtilityFailure.CleanupPending) }
                assertEquals(1, f.runtimes.size)
                f.failStop = false
                f.vm.retryCleanup()
                f.awaitState { !it.cleanupPending }
                f.assertCleanedCandidates(1)
                f.assertNoSelection()
            } finally {
                f.close()
            }
        }

    @Test
    fun `cancellation during HTTP cleans real probe and clears checking without selecting`() =
        runTest {
            val f = fixture()
            try {
                f.httpRelease = CompletableDeferred()
                f.vm.checkAndSelect(Reference)
                withContext(Dispatchers.Default) { withTimeout(10_000) { f.httpEntered.await() } }
                f.awaitItem { it.measurement == ProfileMeasurementUiState.Checking }
                f.vm.cancel()
                withContext(Dispatchers.Default) {
                    withTimeout(10_000) {
                        f.runtimes
                            .single()
                            .finished
                            .await()
                    }
                }
                f.awaitItem { it.measurement == ProfileMeasurementUiState.NotChecked }
                assertNull(f.vm.uiState.value.failure)
                assertFalse(f.vm.uiState.value.cleanupPending)
                f.assertNoSelection()
                f.assertCleanedCandidates(1)
            } finally {
                f.close()
            }
        }

    @Test
    fun `cancelled old check cannot remove new checking after delayed cleanup completion`() =
        runTest {
            val f = fixture()
            try {
                f.httpRelease = CompletableDeferred()
                f.cancelCompletionRelease = CompletableDeferred()
                val scope = checkNotNull(f.vm.viewModelScope.coroutineContext[Job])
                val previousJobs = scope.children.toSet()
                f.vm.check(Reference)
                withContext(Dispatchers.Default) { withTimeout(10_000) { f.httpEntered.await() } }
                val oldOperation = scope.children.single { it !in previousJobs }
                f.vm.cancel()
                withContext(Dispatchers.Default) { withTimeout(10_000) { f.cancelCompletionEntered.await() } }
                f.assertCleanedCandidates(1)

                f.vm.check(Reference)
                withContext(Dispatchers.Default) { withTimeout(10_000) { f.httpCalls.first { it == 2 } } }
                f.awaitItem { it.measurement == ProfileMeasurementUiState.Checking }
                val currentAuthority = f.authority.snapshotAuthority()
                f.cancelCompletionRelease!!.complete(Unit)
                withContext(Dispatchers.Default) { withTimeout(10_000) { f.cancelCompletionReturned.await() } }
                // Public coroutine completion proves the old finally ran while the new HTTP request stays blocked.
                withContext(Dispatchers.Default) { withTimeout(10_000) { oldOperation.join() } }

                assertEquals(
                    ProfileMeasurementUiState.Checking,
                    f.vm.uiState.value.profiles
                        .single()
                        .measurement,
                )
                assertEquals(currentAuthority, f.authority.snapshotAuthority())
                f.assertNoSelection()
                f.httpRelease!!.complete(Unit)
                f.awaitItem { it.measurement is ProfileMeasurementUiState.Measured }
                f.assertCleanedCandidates(2)
            } finally {
                f.cancelCompletionRelease?.complete(Unit)
                f.httpRelease?.complete(Unit)
                f.close()
            }
        }

    @Test
    fun `physical callback ABA during payload rejects otherwise successful selection`() =
        runTest {
            val f = fixture()
            try {
                val original = checkNotNull(f.epoch.capture())
                f.afterHttp = {
                    f.publishLinks(1_420)
                    f.publishLinks(1_500)
                }
                f.vm.checkAndSelect(Reference)
                f.awaitItem {
                    it.measurement ==
                        ProfileMeasurementUiState.Failed(ProfileUtilityFailure.EnvironmentChanged)
                }
                assertNotEquals(original, f.epoch.capture())
                f.assertNoSelection()
                f.assertCleanedCandidates(1)
            } finally {
                f.close()
            }
        }

    @Test
    fun `scope superseded after measurement before reservation rejects selection`() =
        runTest {
            val f = fixture()
            try {
                val before = f.authority.states.value
                f.onPreflight = { f.publishLinks(1_420) }
                f.vm.checkAndSelect(Reference)
                f.awaitItem {
                    it.measurement == ProfileMeasurementUiState.Failed(ProfileUtilityFailure.EnvironmentChanged)
                }
                assertEquals(before, f.authority.states.value)
                f.assertNoSelection()
                f.assertCleanedCandidates(1)
            } finally {
                f.close()
            }
        }

    @Test
    fun `failed checked ACK commit publishes neither recents nor applied configuration`() =
        runTest {
            val f = fixture()
            try {
                f.vm.checkAndSelect(Reference)
                f.awaitItem { it.measurement is ProfileMeasurementUiState.Measured }
                val attempt = f.begin(f.dispatches.single())
                val consumed = f.consumedInput()
                val before = f.authority.states.value
                val stored = f.disk.authorityState()
                f.disk.failAuthorityCommit = true
                val rejected = runCatching { f.acknowledge(attempt, consumed) }.exceptionOrNull()
                assertTrue(rejected is IllegalStateException)
                assertEquals(before, f.authority.states.value)
                assertEquals(stored, f.disk.authorityState())
                assertTrue(f.recents().isEmpty())
                assertTrue(f.applied.applications.value[Mode.Proxy] is RuntimeConfigurationApplication.Applying)
                f.disk.failAuthorityCommit = false
                assertTrue(f.acknowledge(attempt, consumed))
                f.awaitItem { it.applied && it.recentSequence != null }
                assertEquals(listOf(Reference), f.recents().map { it.reference })
                f.assertCleanedCandidates(2)
            } finally {
                f.close()
            }
        }

    @Test
    fun `occupied real Xray owner reports busy without candidate HTTP dispatch or recents`() =
        runTest {
            val f = fixture()
            val release = CountDownLatch(1)
            val occupied =
                f.occupyXray { release.await() }
            try {
                val reference = ProfileUtilityReference.Xray("xray-unit")
                val preparation = f.mutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit)
                f.mutations.mutateCatalog(preparation) { f.xrayProfiles.save("xray-unit", xrayProfile()) }
                f.awaitState { it.profiles.any { item -> item.reference == reference } }
                f.vm.checkAndSelect(reference)
                val terminal =
                    f.awaitState {
                        val result = it.profiles.single { item -> item.reference == reference }.measurement
                        result != ProfileMeasurementUiState.NotChecked && result != ProfileMeasurementUiState.Checking
                    }
                assertEquals(
                    ProfileMeasurementUiState.Failed(ProfileUtilityFailure.Busy),
                    terminal.profiles.single { item -> item.reference == reference }.measurement,
                )
                f.assertNoSelection()
                assertTrue(f.runtimes.isEmpty())
                assertTrue(f.httpUrls.isEmpty())
            } finally {
                release.countDown()
                withContext(Dispatchers.Default) { withTimeout(10_000) { occupied.await() } }
                f.close()
            }
        }

    @Test
    fun `fastest selects minimum successful current payload and records only actual winning ACK`() =
        runTest {
            val f = fixture()
            try {
                val slower = Profile.copy(id = "native-slower", operatorName = "Slower")
                val fastest = Profile.copy(id = "native-fastest", operatorName = "Fastest")
                f.addProfile(slower)
                f.addProfile(fastest)
                f.failedHttpProfiles += Profile.id
                f.latencies[slower.id] = 90L
                f.latencies[fastest.id] = 12L
                f.vm.checkAndSelectFastest()
                val receipt = f.awaitDispatch()
                assertEquals(fastest.id, f.settings.snapshot().relayProfileId)
                assertEquals(1, f.dispatches.size)
                assertTrue(f.recents().isEmpty())
                f.assertCleanedCandidates(3)
                val reference = ProfileUtilityReference.NativeRelay(fastest.id)
                val measured =
                    f.awaitState { state ->
                        state.profiles
                            .single {
                                it.reference == reference
                            }.measurement is ProfileMeasurementUiState.Measured
                    }
                assertEquals(
                    12L,
                    (
                        measured.profiles.single { it.reference == reference }.measurement as
                            ProfileMeasurementUiState.Measured
                    ).latencyMillis,
                )
                val attempt = f.begin(receipt, fastest)
                val consumed = f.consumedInput(fastest)
                assertTrue(f.acknowledge(attempt, consumed))
                assertTrue(f.acknowledge(attempt, consumed))
                assertEquals(listOf(reference), f.recents().map { it.reference })
                f.assertCleanedCandidates(4)
            } finally {
                f.close()
            }
        }

    @Test
    fun `fastest tie retains current catalog order rather than a fabricated preference`() =
        runTest {
            val f = fixture()
            try {
                val another = Profile.copy(id = "native-equal", operatorName = "Equal")
                f.addProfile(another)
                val first =
                    f.vm.uiState.value.profiles
                        .first()
                        .reference as ProfileUtilityReference.NativeRelay
                f.latencies[Profile.id] = 30L
                f.latencies[another.id] = 30L
                f.vm.checkAndSelectFastest()
                f.awaitDispatch()
                assertEquals(first.profileId, f.settings.snapshot().relayProfileId)
                assertEquals(1, f.dispatches.size)
                assertTrue(f.recents().isEmpty())
                f.assertCleanedCandidates(2)
            } finally {
                f.close()
            }
        }

    @Test
    fun `fastest with all HTTP failures preserves selection authority and history`() =
        runTest {
            val f = fixture()
            try {
                val another = Profile.copy(id = "native-failed")
                f.addProfile(another)
                val before = f.settings.snapshot()
                f.failedHttpProfiles += listOf(Profile.id, another.id)
                f.vm.checkAndSelectFastest()
                f.awaitState { state -> state.profiles.all { it.measurement is ProfileMeasurementUiState.Failed } }
                assertEquals(before, f.settings.snapshot())
                f.assertNoSelection()
                f.assertCleanedCandidates(2)
            } finally {
                f.close()
            }
        }

    @Test
    fun `fastest cancellation retires candidate without applying a partial winner`() =
        runTest {
            val f = fixture()
            try {
                f.addProfile(Profile.copy(id = "native-other"))
                f.httpRelease = CompletableDeferred()
                f.vm.checkAndSelectFastest()
                withContext(Dispatchers.Default) { withTimeout(10_000) { f.httpEntered.await() } }
                f.vm.cancel()
                withContext(Dispatchers.Default) {
                    withTimeout(10_000) {
                        f.runtimes
                            .single()
                            .finished
                            .await()
                    }
                }
                f.awaitState { state -> state.profiles.none { it.measurement == ProfileMeasurementUiState.Checking } }
                f.assertNoSelection()
                f.assertCleanedCandidates(1)
            } finally {
                f.close()
            }
        }

    @Test
    fun `fastest cleanup failure blocks applying an earlier successful result`() =
        runTest {
            val f = fixture()
            try {
                val last = Profile.copy(id = "zz-cleanup-failure")
                f.addProfile(last)
                f.failedStopProfiles += last.id
                f.vm.checkAndSelectFastest()
                f.awaitState { it.cleanupPending }
                f.assertNoSelection()
                assertEquals(2, f.runtimes.size)
                assertTrue(
                    f.runtimes
                        .first()
                        .finished.isCompleted,
                )
                assertFalse(
                    f.runtimes
                        .last()
                        .finished.isCompleted,
                )
                f.failedStopProfiles.clear()
                f.vm.retryCleanup()
                f.awaitState { !it.cleanupPending }
                f.assertCleanedCandidates(2)
            } finally {
                f.failedStopProfiles.clear()
                f.close()
            }
        }

    @Test
    fun `fastest newer Stop during payload prevents applying any earlier measurement`() =
        runTest {
            val f = fixture()
            try {
                f.addProfile(Profile.copy(id = "zz-changed"))
                var stop: com.poyka.ripdpi.data.RuntimeStopReceipt? = null
                f.afterHttp = {
                    if (f.httpUrls.size == 2) stop = f.authority.reserveStop()
                }
                f.vm.checkAndSelectFastest()
                f.awaitState { state -> state.failure == ProfileUtilityFailure.EnvironmentChanged }
                assertTrue(f.dispatches.isEmpty())
                assertTrue(f.recents().isEmpty())
                assertTrue(f.authority.isCurrent(checkNotNull(stop)))
                f.assertCleanedCandidates(2)
            } finally {
                f.close()
            }
        }

    @Test
    fun `fastest physical change between completed candidates rejects a mixed round`() =
        verifyBetweenCandidatesChange { publishLinks(1_400) }

    @Test
    fun `fastest policy change between completed candidates rejects a mixed round`() =
        verifyBetweenCandidatesChange { changePolicy() }

    private fun verifyBetweenCandidatesChange(change: suspend Fixture.() -> Unit) =
        runTest {
            val f = fixture()
            var observer: Job? = null
            try {
                f.addProfile(Profile.copy(id = "zz-next-candidate"))
                f.latencies[Profile.id] = 50L
                f.latencies["zz-next-candidate"] = 7L
                val moved = CompletableDeferred<Unit>()
                observer =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        f.vm.uiState.collect { state ->
                            if (!moved.isCompleted &&
                                state.profiles.single { it.reference == Reference }.measurement is
                                    ProfileMeasurementUiState.Measured
                            ) {
                                assertTrue(
                                    f.runtimes
                                        .single()
                                        .finished.isCompleted,
                                )
                                f.change()
                                moved.complete(Unit)
                            }
                        }
                    }
                f.httpRelease = CompletableDeferred()
                val scope = checkNotNull(f.vm.viewModelScope.coroutineContext[Job])
                val originalJobs = scope.children.toSet()
                f.vm.checkAndSelectFastest()
                withContext(Dispatchers.Default) { withTimeout(10_000) { f.httpEntered.await() } }
                val operation = scope.children.single { it !in originalJobs }
                f.httpRelease!!.complete(Unit)
                withContext(Dispatchers.Default) { withTimeout(10_000) { operation.join() } }
                assertTrue(
                    "The environment change occurred only after a completed measured candidate",
                    moved.isCompleted,
                )
                val terminal =
                    f.awaitState { state ->
                        state.profiles.none { it.measurement == ProfileMeasurementUiState.Checking }
                    }
                assertEquals(ProfileUtilityFailure.EnvironmentChanged, terminal.failure)
                f.assertNoSelection()
                f.assertCleanedCandidates(1)
            } finally {
                observer?.cancel()
                f.close()
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProfileUtilityCatalogRecoveryTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `catalog read failure shows recovery and retry restores saved profiles`() =
        runTest {
            val disk = FaultingPreferencesContext(RuntimeEnvironment.getApplication())
            val f = Fixture(disk, MemorySettings(), MemoryRelayCredentials())
            try {
                f.stores.relayProfiles.save(Profile)
                disk.failCatalogRead = true
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.vm.uiState.collect {} }
                val state = f.awaitState { it.catalogState == ProfileCatalogState.Failed }
                assertFalse(state.loading)
                assertEquals(ProfileUtilityFailure.Persistence, state.failure)
                assertTrue(state.profiles.isEmpty())
                assertTrue(f.dispatches.isEmpty())
                disk.failCatalogRead = false
                f.vm.retryCatalog()
                val ready = f.awaitState { it.catalogState == ProfileCatalogState.Ready }
                assertEquals(Reference, ready.profiles.single().reference)
                assertEquals(null, ready.failure)
                assertTrue(f.dispatches.isEmpty())
            } finally {
                disk.failCatalogRead = false
                f.close()
            }
        }
}

private suspend fun TestScope.fixture(
    disk: FaultingPreferencesContext = FaultingPreferencesContext(RuntimeEnvironment.getApplication()),
    settings: MemorySettings = MemorySettings(),
    credentials: MemoryRelayCredentials = MemoryRelayCredentials(),
): Fixture {
    val fixture = Fixture(disk, settings, credentials)
    fixture.stores.relayProfiles.save(Profile)
    credentials.save(Credentials)
    fixture.mutations.recover()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.vm.uiState.collect {} }
    fixture.awaitState { !it.loading && it.profiles.any { item -> item.reference == Reference } }
    fixture.vm.updateUrl(ProbeUrl)
    fixture.awaitState { it.canMeasure }
    return fixture
}

private const val ProbeUrl = "https://unit.example/payload"
private const val AppliedAt = 1_800_000_000_000L
private val Profile =
    RelayProfileRecord(
        id = "unit-trojan",
        kind = "trojan",
        server = "relay.example",
        serverName = "relay.example",
        operatorName = "Unit relay",
    )
private val Reference = ProfileUtilityReference.NativeRelay(Profile.id)
private val Credentials = RelayCredentialRecord(profileId = Profile.id, trojanPassword = "fixture-unit-only-credential")

private fun xrayProfile() =
    XrayProfile(
        name = "Unit Xray",
        outbound =
            XrayProfile.Outbound(
                serverAddress = "xray.example",
                serverPort = 443,
                uuid = "9f37c02d-c9f5-408f-96b2-c71671e90435",
                security = XrayProfile.Security.TLS,
                network = XrayProfile.Network.TCP,
                tls = XrayProfile.Tls(serverName = "xray.example"),
            ),
    )

private class Fixture(
    val disk: FaultingPreferencesContext,
    val settings: MemorySettings,
    val credentials: MemoryRelayCredentials,
) : AutoCloseable {
    val authority =
        PauseIntentAuthority(
            CheckedPauseAuthorityPersistence(disk),
            object : PauseClock {
                override fun read() = PauseClockReading(AppliedAt, 10_000, 7)
            },
            RuntimeIntentLinearizer(),
        )
    val selector = SelectorActiveGroupStore(disk, authority)
    val stores =
        ProfileMutationStores(
            settings,
            SharedPreferencesRelayProfileStore(disk),
            credentials,
            SharedPreferencesWarpProfileStore(disk),
            rejectingPort<WarpCredentialStore>(),
            SharedPreferencesWarpEndpointStore(disk),
            SharedPreferencesXrayProfileMetadataStore(disk),
            MemoryXraySecrets(),
            SharedPreferencesXrayProviderSelectionStore(disk),
            SharedPreferencesBootSessionStateStore(disk.getSharedPreferences("unit-boot", Context.MODE_PRIVATE)),
            MemoryGroupBlob(),
            selector,
        )
    val mutations =
        ProfileMutationRecoveryCoordinator(
            stores,
            rejectingPort<AwgProfileDao>(),
            rejectingPort<AwgCredentialStore>(),
            MemoryJournal(),
            ProfileMutationGenerationPublisher(),
            authority,
        )
    val groups = SharedPreferencesProxyGroupRepository(stores.groupBlob, mutations)
    val selectionStore = SharedPreferencesSelectorSelectionStore(disk, mutations, authority, selector)
    val xrayProfiles = DefaultDurableXrayProfileStore(stores.xrayMetadata, stores.xraySecrets)
    val runtimeRegistry = DefaultServiceRuntimeRegistry()
    private val connectivity = shadowOf(disk.getSystemService(ConnectivityManager::class.java))
    private val previousCallbacks = connectivity.networkCallbacks.toSet()
    val epoch = CandidateRelayNetworkEpoch(disk)
    private val physical = ShadowNetwork.newInstance(817)
    private val callback = (connectivity.networkCallbacks.toSet() - previousCallbacks).single()

    @Volatile private var environment =
        CandidateRelayProbeEnvironment(false, "chrome_stable", emptyMap(), false, false)

    suspend fun changePolicy() {
        settings.update { setTlsFingerprintProfile("firefox") }
        environment = environment.copy(tlsProfile = "firefox")
    }

    val runtimes = CopyOnWriteArrayList<ControlledRelayRuntime>()
    val httpUrls = CopyOnWriteArrayList<String>()
    val latencies = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val httpClockNanos =
        java.util.concurrent.atomic
            .AtomicLong()
    val failedHttpProfiles =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    val failedStopProfiles =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    private val firstDispatch = CompletableDeferred<RuntimeActivationReceipt>()
    val httpEntered = CompletableDeferred<Unit>()
    val httpCalls = MutableStateFlow(0)
    var cancelCompletionRelease: CompletableDeferred<Unit>? = null
    val cancelCompletionEntered = CompletableDeferred<Unit>()
    val cancelCompletionReturned = CompletableDeferred<Unit>()

    @Volatile var httpRelease: CompletableDeferred<Unit>? = null

    @Volatile var afterHttp: () -> Unit = {}

    @Volatile var failStop = false

    @Volatile var onPreflight: () -> Unit = {}
    private val http =
        construct(
            "com.poyka.ripdpi.services.CandidateHttpPayloadProbe",
            internalTcpProbe { endpoint, url ->
                assertEquals("127.0.0.1", endpoint.host)
                assertEquals(1234, endpoint.port)
                httpUrls += url
                httpCalls.value += 1
                httpEntered.complete(Unit)
                httpRelease?.await()
                afterHttp()
                val profileId = runtimes.last().profileId
                httpClockNanos.addAndGet((latencies[profileId] ?: 0L) * 1_000_000L)
                construct(
                    "com.poyka.ripdpi.services.RelayTcpProbeResult",
                    profileId !in failedHttpProfiles,
                    204,
                    null,
                )
            },
            { httpClockNanos.get() },
        ) as CandidateHttpPayloadProbe
    private val probeFixtures =
        ProfileUtilityProbeFixtures(
            http,
            suspend { environment },
            { failStop || runtimes.last().profileId in failedStopProfiles },
            runtimes::add,
        )
    val probe = probeFixtures.relay
    private val controlledMeasurement: CandidateRelayMeasurements =
        object : CandidateRelayMeasurements by probe {
            override suspend fun measure(
                profile: RelayProfileRecord,
                credentials: RelayCredentialRecord,
                probeUrl: String,
            ): CandidateRelayMeasurement =
                try {
                    probe.measure(profile, credentials, probeUrl)
                } catch (cancelled: CancellationException) {
                    val release = cancelCompletionRelease
                    if (release != null) {
                        withContext(NonCancellable) {
                            cancelCompletionEntered.complete(Unit)
                            release.await()
                            cancelCompletionReturned.complete(Unit)
                        }
                    }
                    throw cancelled
                }
        }

    fun occupyXray(block: () -> Unit) = probeFixtures.occupyXray(block)

    private val xrayProbe = probeFixtures.xray
    private val requestedCapture = createRequestedCapture()
    private val measured =
        construct(
            "com.poyka.ripdpi.services.MeasuredActivationRegistry",
            authority,
            mutations,
            requestedCapture,
            settings,
        ) as MeasuredActivationRegistry
    private val consumer = construct("com.poyka.ripdpi.services.RuntimeAppliedReceiptConsumer", authority, measured)
    private val composite = construct("com.poyka.ripdpi.services.AppliedRuntimeConfigurationStore", consumer)
    val applied = composite as AppliedRuntimeConfigurationSource
    val dispatches = CopyOnWriteArrayList<RuntimeActivationReceipt>()
    private val controller =
        port(ServiceController::class.java) { name, args ->
            when (name) {
                "preflight" -> {
                    onPreflight()
                    ServiceStartPreflightResult.Allowed
                }

                "startPrepared" -> {
                    val receipt = args[1] as RuntimeActivationReceipt
                    assertTrue(authority.isCurrent(receipt))
                    dispatches += receipt
                    firstDispatch.complete(receipt)
                    ServiceStartResult.Accepted(receipt)
                }

                else -> {
                    error("Unexpected service command: $name")
                }
            }
        }
    private val measurement =
        ProfileUtilityMeasurementCoordinator(
            mutations,
            authority,
            stores.relayProfiles,
            credentials,
            groups,
            controlledMeasurement,
            xrayProbe,
            xrayProfiles,
            stores.xrayMetadata,
            stores.xraySecrets,
            object : NetworkFingerprintProvider {
                override fun capture() = NetworkFingerprint("wifi", true, false, "off", listOf("192.0.2.53"))
            },
            epoch,
            runtimeRegistry,
            applied,
        )
    val vm =
        ProfileUtilityViewModel(
            ProfileUtilityCatalogReader(mutations, stores, authority),
            authority,
            mutations,
            applied,
            measurement,
            MeasuredProfileActivationCoordinator(
                mutations,
                settings,
                authority,
                controller,
                measured,
                runtimeRegistry,
            ),
            controlledMeasurement,
            xrayProbe,
        )
    private val viewModels = ViewModelStore().apply { put("profiles", vm) }

    init {
        assertNull(epoch.capture())
        callback.onAvailable(physical)
        assertNull(epoch.capture())
        callback.onCapabilitiesChanged(
            physical,
            NetworkCapabilities().apply {
                invokePublic(this, "addTransportType", NetworkCapabilities.TRANSPORT_WIFI)
                listOf(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                    NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
                ).forEach { invokePublic(this, "addCapability", it) }
            },
        )
        assertNull(epoch.capture())
        publishLinks(1_500)
        assertNull(epoch.capture())
        callback.onBlockedStatusChanged(physical, false)
        assertTrue(epoch.capture() != null)
    }

    fun publishLinks(mtu: Int) {
        val route =
            RouteInfo::class.java
                .getDeclaredConstructor(
                    IpPrefix::class.java,
                    InetAddress::class.java,
                    String::class.java,
                ).newInstance(
                    IpPrefix::class.java
                        .getDeclaredConstructor(
                            InetAddress::class.java,
                            Int::class.javaPrimitiveType,
                        ).newInstance(InetAddress.getByName("0.0.0.0"), 0),
                    InetAddress.getByName("192.0.2.1"),
                    "unit0",
                )
        callback.onLinkPropertiesChanged(
            physical,
            LinkProperties().apply {
                interfaceName = "unit0"
                this.mtu = mtu
                invokePublic(
                    this,
                    "addLinkAddress",
                    LinkAddress::class.java
                        .getDeclaredConstructor(
                            InetAddress::class.java,
                            Int::class.javaPrimitiveType,
                        ).newInstance(InetAddress.getByName("192.0.2.2"), 24),
                )
                addRoute(route)
                setDnsServers(listOf(InetAddress.getByName("192.0.2.53")))
            },
        )
    }

    private fun createRequestedCapture(): Any {
        val selectorResolver =
            construct(
                "com.poyka.ripdpi.services.DefaultSelectorRelayRuntimeProfileResolver",
                selector,
                selectionStore,
                groups,
            )
        val catalogs =
            construct(
                "com.poyka.ripdpi.services.RuntimeConfigurationCatalogCapture",
                mutations,
                stores.relayProfiles,
                credentials,
                stores.warpCredentials,
                xrayProfiles,
                stores.xraySelection,
                selectorResolver,
            )
        val compiled =
            DestinationRoutingPolicyCompiler.compile(
                emptyList(),
            ) as DestinationRoutingPolicyCompileResult.Success
        // Invoke the real capture factory: no direct HMAC/digest/proof construction or private-field access.
        return construct(
            "com.poyka.ripdpi.services.RequestedRuntimeConfigurationCapture",
            construct("com.poyka.ripdpi.services.RuntimeConfigurationIdentityFactory"),
            catalogs,
            DestinationRoutingPolicySource { DestinationRoutingPolicySnapshot.Available(compiled.policy) },
            ProxySessionSecretResolver(rejectingPort<WsTunnelWorkerCredentialStore>()),
            groups,
            authority,
            object : RuntimeExperimentSelectionProvider {
                override fun current() = RuntimeExperimentSelection()
            },
        )
    }

    suspend fun addProfile(profile: RelayProfileRecord) {
        mutations.upsertRelay(
            mutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
            profile,
            Credentials.copy(profileId = profile.id),
            enabled = false,
            select = false,
        )
        awaitState { state ->
            state.profiles.any { it.reference == ProfileUtilityReference.NativeRelay(profile.id) }
        }
    }

    suspend fun awaitDispatch(): RuntimeActivationReceipt =
        withContext(Dispatchers.Default) { withTimeout(10_000) { firstDispatch.await() } }

    suspend fun begin(
        receipt: RuntimeActivationReceipt,
        profile: RelayProfileRecord = Profile,
    ): RuntimeConfigurationAttempt {
        val requested = invokeSuspend(requestedCapture, "capture", Mode.Proxy, settings.snapshot(), null)
        val identity = invokePublic(checkNotNull(requested), "getIdentity")
        val selection = RuntimeConfigurationSelection("native", relayKind = profile.kind, profileId = profile.id)
        val attempt =
            RuntimeConfigurationAttempt(
                "unit-runtime",
                1,
                Mode.Proxy,
                selection,
                RuntimeConfigurationApplyReason.InitialStart,
                RuntimeAppliedIntent.Activation(receipt),
                checkNotNull(authority.states.value).profileUtility.catalogGeneration,
            )
        assertEquals(true, invokePublic(composite, "begin", attempt, identity))
        return attempt
    }

    suspend fun consumedInput(profile: RelayProfileRecord = Profile): CandidateRelayMeasurement.Succeeded {
        val measured = withContext(Dispatchers.IO) { probe.measure(profile, Credentials, ProbeUrl) }
        assertTrue(
            "Consumed unit input must itself complete real candidate cleanup: $measured",
            measured is CandidateRelayMeasurement.Succeeded,
        )
        return measured as CandidateRelayMeasurement.Succeeded
    }

    fun acknowledge(
        attempt: RuntimeConfigurationAttempt,
        consumed: CandidateRelayMeasurement.Succeeded?,
    ): Boolean {
        val configuration =
            AppliedRuntimeConfiguration(
                attempt.runtimeId,
                attempt.revision,
                AppliedAt,
                Mode.Proxy,
                attempt.requestedSelection,
                attempt.requestedSelection,
                RuntimeConfigurationDns("plain", "system"),
                RuntimeConfigurationStrategy(false),
                attempt.reason,
            )
        return invokePublic(
            composite,
            "acknowledge",
            attempt,
            configuration,
            consumed?.configurationProof,
        ) as Boolean
    }

    fun recents() = checkNotNull(authority.states.value).profileUtility.recents

    fun assertNoSelection() {
        assertTrue(dispatches.isEmpty())
        assertTrue(recents().isEmpty())
        assertNull(checkNotNull(authority.states.value).command)
    }

    fun assertCleanedCandidates(expected: Int) {
        assertEquals(expected, runtimes.size)
        runtimes.forEach {
            assertTrue(it.stopCalls.get() > 0)
            assertTrue(it.finished.isCompleted)
        }
        assertFalse(probe.cleanupPending.value)
    }

    suspend fun awaitState(predicate: (ProfileUtilityUiState) -> Boolean): ProfileUtilityUiState =
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.uiState.first(predicate) } }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Expected UI state was not observed; actual=${vm.uiState.value}", timeout)
        }

    suspend fun awaitItem(predicate: (ProfileUtilityItem) -> Boolean): ProfileUtilityItem =
        awaitState { state -> state.profiles.singleOrNull { it.reference == Reference }?.let(predicate) == true }
            .profiles
            .single { it.reference == Reference }

    override fun close() {
        failStop = false
        disk.failAuthorityCommit = false
        viewModels.clear()
        callback.onLost(physical)
        disk.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
    }
}

private class MemorySettings : AppSettingsRepository {
    private val state =
        MutableStateFlow(
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setRipdpiMode(Mode.Proxy.preferenceValue)
                .build(),
        )
    override val settings = state

    override suspend fun snapshot() = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class MemoryRelayCredentials : RelayCredentialStore {
    private val values = mutableMapOf<String, RelayCredentialRecord>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        values[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() {
        values.clear()
    }
}

private class MemoryXraySecrets : XrayProfileSecretStore {
    private val values = mutableMapOf<String, XrayProfileSecretRecord>()

    override suspend fun load(profileId: String) = values[profileId]

    override suspend fun save(record: XrayProfileSecretRecord) {
        values[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        values.remove(profileId)
    }

    override suspend fun clearAll() {
        values.clear()
    }
}

private class MemoryGroupBlob : ProxyGroupBlobStore {
    private var value: String? = null

    override fun read() = value

    override fun write(json: String) {
        value = json
    }

    override fun clear() {
        value = null
    }
}

private class MemoryJournal : ProfileMutationJournal {
    private var value: PendingProfileMutation? = null

    override suspend fun prepare(mutation: PendingProfileMutation) {
        check(value == null)
        value = mutation
    }

    override suspend fun pending() = value

    override suspend fun replace(
        expectedMutationId: String,
        mutation: PendingProfileMutation,
    ) {
        check(value?.mutationId == expectedMutationId)
        value = mutation
    }

    override suspend fun complete(mutationId: String) {
        check(value?.mutationId == mutationId)
        value = null
    }

    override suspend fun clearForReset() {
        value = null
    }
}

private class FaultingPreferencesContext(
    base: Context,
) : ContextWrapper(base) {
    private val prefix = "profile-vm-unit-${UUID.randomUUID()}-"

    @Volatile var failAuthorityCommit = false

    @Volatile var failCatalogRead = false

    override fun getSharedPreferences(
        name: String?,
        mode: Int,
    ): SharedPreferences {
        val delegate = super.getSharedPreferences(prefix + name, mode)
        return object : SharedPreferences by delegate {
            override fun getString(
                key: String?,
                defValue: String?,
            ): String? {
                if (name == "relay_profile_cache" && failCatalogRead) error("Controlled catalog storage failure")
                return delegate.getString(key, defValue)
            }

            override fun edit(): SharedPreferences.Editor =
                FaultingEditor(delegate.edit()) {
                    name == "pause_intent_authority" && failAuthorityCommit
                }
        }
    }

    fun authorityState() = getSharedPreferences("pause_intent_authority", Context.MODE_PRIVATE).getString("state", null)
}

private class FaultingEditor(
    private val delegate: SharedPreferences.Editor,
    private val fails: () -> Boolean,
) : SharedPreferences.Editor by delegate {
    override fun putString(
        key: String?,
        value: String?,
    ) = apply { delegate.putString(key, value) }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ) = apply { delegate.putStringSet(key, values) }

    override fun putBoolean(
        key: String?,
        value: Boolean,
    ) = apply { delegate.putBoolean(key, value) }

    override fun putInt(
        key: String?,
        value: Int,
    ) = apply { delegate.putInt(key, value) }

    override fun putLong(
        key: String?,
        value: Long,
    ) = apply { delegate.putLong(key, value) }

    override fun putFloat(
        key: String?,
        value: Float,
    ) = apply { delegate.putFloat(key, value) }

    override fun remove(key: String?) = apply { delegate.remove(key) }

    override fun clear() = apply { delegate.clear() }

    override fun commit(): Boolean = !fails() && delegate.commit()

    override fun apply() {
        check(commit()) { "Unit preference commit failed" }
    }
}

/** Reflect only real constructors/public operations across module internals; never opaque proof/state fields. */
private fun construct(
    name: String,
    vararg args: Any?,
): Any {
    val type = Class.forName(name)
    val constructor =
        type.declaredConstructors.single { ctor ->
            ctor.parameterCount == args.size &&
                ctor.parameterTypes.zip(args).all { (parameter, value) ->
                    value == null || parameter.isInstance(value) ||
                        (parameter == Boolean::class.javaPrimitiveType && value is Boolean) ||
                        (parameter == Int::class.javaPrimitiveType && value is Int) ||
                        (parameter == Long::class.javaPrimitiveType && value is Long)
                }
        }
    return try {
        constructor.isAccessible = true
        constructor.newInstance(*args)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}

private fun invokePublic(
    target: Any,
    name: String,
    vararg args: Any?,
): Any? {
    val method =
        target.javaClass.methods.single {
            it.name.substringBefore('$') == name &&
                it.parameterCount == args.size
        }
    return try {
        method.invoke(target, *args)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}

private suspend fun invokeSuspend(
    target: Any,
    name: String,
    vararg args: Any?,
): Any? = suspendCoroutineUninterceptedOrReturn { continuation -> invokePublic(target, name, *args, continuation) }

private inline fun <reified T> rejectingPort(): T =
    port(T::class.java) { name, _ -> error("Unexpected unit store/native operation: $name") }

private fun <T> port(
    type: Class<T>,
    call: (String, Array<out Any?>) -> Any?,
): T =
    type.cast(
        Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, args -> call(method.name, args.orEmpty()) },
    )

private fun internalTcpProbe(block: suspend (RelayProbeEndpoint, String) -> Any): Any {
    val type = Class.forName("com.poyka.ripdpi.services.RelayTcpProbe")
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        check(method.name == "probe") { "Unexpected TCP verifier operation: ${method.name}" }
        @Suppress("UNCHECKED_CAST")
        val invoke = block as (RelayProbeEndpoint, String, kotlin.coroutines.Continuation<Any>) -> Any?
        @Suppress("UNCHECKED_CAST")
        invoke(args!![0] as RelayProbeEndpoint, args[1] as String, args[2] as kotlin.coroutines.Continuation<Any>)
    }
}
