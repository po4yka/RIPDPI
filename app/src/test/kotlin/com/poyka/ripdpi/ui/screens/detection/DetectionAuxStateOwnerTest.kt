package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.core.detection.community.CommunityStatsRefreshResult
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionAuxStateOwnerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testScope = CoroutineScope(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private fun reducer(
        initial: DetectionCheckUiState = DetectionCheckUiState(),
    ): Pair<MutableStateFlow<DetectionCheckUiState>, DetectionCheckStateReducer> {
        val flow = MutableStateFlow(initial)
        return flow to DetectionCheckStateReducer(flow)
    }

    @Test
    fun `observeDetectionSettings folds flags and reformats existing result`() =
        runTest {
            val result = detectionResult(Verdict.NOT_DETECTED)
            val (flow, red) = reducer(DetectionCheckUiState(result = result))
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository =
                        FakeDetectionAppSettingsRepository(
                            com.poyka.ripdpi.proto.AppSettings
                                .getDefaultInstance()
                                .toBuilder()
                                .apply {
                                    detectionCheckPrivacyModeEnabled = true
                                    detectionCheckDebugModeEnabled = true
                                    detectionCheckCdnPullingEnabled = true
                                }.build(),
                        ),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository = FakeCommunityStatsRepository(),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.observeDetectionSettings()
            advanceUntilIdle()

            assertTrue(flow.value.privacyModeEnabled)
            assertTrue(flow.value.debugModeEnabled)
            assertTrue(flow.value.cdnPullingEnabled)
            assertTrue(flow.value.reportText != null)
            assertTrue(flow.value.debugReportText != null)
        }

    @Test
    fun `observeDetectionSettings leaves debugReport null when no result`() =
        runTest {
            val (flow, red) = reducer()
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository = FakeCommunityStatsRepository(),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.observeDetectionSettings()
            advanceUntilIdle()

            assertNull(flow.value.reportText)
            assertNull(flow.value.debugReportText)
        }

    @Test
    fun `loadCommunityState only writes when stats non-null`() =
        runTest {
            val (flow, red) = reducer()
            val withNull =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository = FakeCommunityStatsRepository(cachedOrLocal = null),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )
            withNull.loadCommunityState()
            advanceUntilIdle()
            assertNull(flow.value.communityStats)

            val (flow2, red2) = reducer()
            val withStats =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red2,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository =
                        FakeCommunityStatsRepository(cachedOrLocal = CommunityStats(totalReports = 3)),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )
            withStats.loadCommunityState()
            advanceUntilIdle()
            assertEquals(3, flow2.value.communityStats?.totalReports)
        }

    @Test
    fun `refreshCommunityStats applies local then remote override and clears loading`() =
        runTest {
            val (flow, red) = reducer()
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository =
                        FakeCommunityStatsRepository(
                            refreshResult =
                                CommunityStatsRefreshResult(
                                    localStats = CommunityStats(totalReports = 1),
                                    remoteStats = CommunityStats(totalReports = 9),
                                    remoteError = null,
                                ),
                        ),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.refreshCommunityStats()
            advanceUntilIdle()

            assertEquals(9, flow.value.communityStats?.totalReports)
            assertFalse(flow.value.communityStatsLoading)
            assertNull(flow.value.communityStatsError)
        }

    @Test
    fun `refreshCommunityStats sets error only when no existing stats on null remote`() =
        runTest {
            val (flow, red) =
                reducer(DetectionCheckUiState(communityStats = CommunityStats(totalReports = 4)))
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository =
                        FakeCommunityStatsRepository(
                            refreshResult =
                                CommunityStatsRefreshResult(
                                    localStats = CommunityStats(totalReports = 4),
                                    remoteStats = null,
                                    remoteError = IllegalStateException("boom"),
                                ),
                        ),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.refreshCommunityStats()
            advanceUntilIdle()

            assertNull(flow.value.communityStatsError)
            assertFalse(flow.value.communityStatsLoading)
        }

    @Test
    fun `refreshCommunityStats exception path sets error only when no existing stats`() =
        runTest {
            val (flow, red) = reducer()
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository =
                        FakeCommunityStatsRepository(refreshThrows = IllegalStateException("io")),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.refreshCommunityStats()
            advanceUntilIdle()

            assertEquals("io", flow.value.communityStatsError)
            assertFalse(flow.value.communityStatsLoading)
        }

    @Test
    fun `refreshCommunityStats rethrows cancellation`() =
        runTest {
            val (flow, red) = reducer()
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository =
                        FakeCommunityStatsRepository(refreshThrows = CancellationException("cancel")),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.refreshCommunityStats()
            advanceUntilIdle()

            // Cancellation must not be converted into an error state.
            assertNull(flow.value.communityStatsError)
        }

    @Test
    fun `dismissOnboarding marks shown and clears flag`() =
        runTest {
            val (flow, red) = reducer(DetectionCheckUiState(showOnboarding = true))
            val prefs = FakeDetectionCheckPreferenceStore()
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository = FakeCommunityStatsRepository(),
                    detectionCheckPreferences = prefs,
                )

            owner.dismissOnboarding()

            assertEquals(1, prefs.markOnboardingShownCount)
            assertFalse(flow.value.showOnboarding)
        }

    @Test
    fun `saveToHistory uses fingerprint scope key and reloads history`() =
        runTest {
            val (_, red) = reducer()
            val history = FakeDetectionHistoryRepository()
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(wifiFingerprint()),
                    historyStore = history,
                    communityStatsRepository = FakeCommunityStatsRepository(),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.saveToHistory(detectionResult(Verdict.NOT_DETECTED), score = 80)
            advanceUntilIdle()

            assertEquals(1, history.saved.size)
            assertEquals("wifi/wifi", history.saved.first().networkSummary)
            assertEquals(80, history.saved.first().stealthScore)
            assertTrue(history.loadLatestCount >= 1)
        }

    @Test
    fun `saveToHistory falls back to Unknown summary when no fingerprint`() =
        runTest {
            val (_, red) = reducer()
            val history = FakeDetectionHistoryRepository()
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(null),
                    historyStore = history,
                    communityStatsRepository = FakeCommunityStatsRepository(),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.saveToHistory(detectionResult(Verdict.NOT_DETECTED), score = 10)
            advanceUntilIdle()

            assertEquals("unknown", history.saved.first().networkFingerprint)
            assertEquals("Unknown", history.saved.first().networkSummary)
        }

    @Test
    fun `applyAllFixes clears suggested fixes`() =
        runTest {
            val (flow, red) =
                reducer(DetectionCheckUiState(suggestedFixes = emptyList()))
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = FakeDetectionAppSettingsRepository(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository = FakeCommunityStatsRepository(),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.applyAllFixes()
            advanceUntilIdle()

            assertTrue(flow.value.suggestedFixes.isEmpty())
        }

    @Test
    fun `set lambdas write the matching settings field`() =
        runTest {
            val (_, red) = reducer()
            val settingsRepo = FakeDetectionAppSettingsRepository()
            val owner =
                DetectionAuxStateOwner(
                    scope = testScope,
                    reducer = red,
                    appSettingsRepository = settingsRepo,
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    historyStore = FakeDetectionHistoryRepository(),
                    communityStatsRepository = FakeCommunityStatsRepository(),
                    detectionCheckPreferences = FakeDetectionCheckPreferenceStore(),
                )

            owner.setPrivacyModeEnabled(true)
            owner.setCdnPullingEnabled(true)
            owner.setDebugModeEnabled(true)
            advanceUntilIdle()

            val snapshot = settingsRepo.snapshot()
            assertTrue(snapshot.detectionCheckPrivacyModeEnabled)
            assertTrue(snapshot.detectionCheckCdnPullingEnabled)
            assertTrue(snapshot.detectionCheckDebugModeEnabled)
        }
}
