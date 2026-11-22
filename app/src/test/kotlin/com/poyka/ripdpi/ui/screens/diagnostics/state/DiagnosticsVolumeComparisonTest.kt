package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeServiceStateStore
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.TransferEvidence
import com.poyka.ripdpi.diagnostics.TransferMeasurement
import com.poyka.ripdpi.diagnostics.TransferProgressSample
import com.poyka.ripdpi.diagnostics.TransferVolumeComparisonInput
import com.poyka.ripdpi.diagnostics.TransferVolumeComparisonProfileId
import com.poyka.ripdpi.diagnostics.VerifiedDownloadInput
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.ui.screens.diagnostics.volumeTransferLegTitleResource
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsVolumeComparisonTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profileId = TransferVolumeComparisonProfileId
    private val input =
        TransferVolumeComparisonInput(
            "192.0.2.8",
            VerifiedDownloadInput("https://fixture.example/short", "7", "a".repeat(64)),
            VerifiedDownloadInput("https://fixture.example/long", "64", "b".repeat(64)),
        )

    @Test
    fun `manual controls start only a complete RAW pair`() =
        runTest {
            val manager = FakeDiagnosticsManager()
            manager.profilesState.value = listOf(bundledProfile())
            val paths = mutableListOf<ScanPathMode>()
            manager.scanController.onStartScan = { path, profile ->
                assertEquals(profileId, profile)
                paths += path
                DiagnosticsManualScanStartResult.Started("volume-comparison")
            }
            val viewModel =
                createDiagnosticsViewModel(
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                )
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.selectionActions.selectProfile(profileId)
            viewModel.selectionActions.manualProbeInput.onVolumeComparisonChanged(
                input.copy(long = VerifiedDownloadInput()),
            )
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.scan.runRawEnabled)
            viewModel.scanActions.startRawScan()
            advanceUntilIdle()
            assertTrue(paths.isEmpty())

            viewModel.selectionActions.manualProbeInput.onVolumeComparisonChanged(input)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.scan.runRawEnabled)
            assertFalse(viewModel.uiState.value.scan.runInPathEnabled)
            viewModel.scanActions.startInPathScan()
            advanceUntilIdle()
            assertTrue(paths.isEmpty())

            viewModel.scanActions.startRawScan()
            advanceUntilIdle()
            assertEquals(listOf(ScanPathMode.RAW_PATH), paths)
            assertEquals(input, manager.scanController.lastScanTargetOverrides?.volumeComparison)
        }

    @Test
    fun `stalled long response retains bytes and body timings in scan and history models`() {
        val measure =
            TransferMeasurement(
                runIndex = 1,
                runCount = 1,
                receivedBodyByteCount = 41,
                elapsedMs = 1_234,
                expectedBodyByteCount = 64,
                firstBodyByteMs = 20,
                lastBodyProgressMs = 110,
                terminationReason = "idle_timeout",
                samples = listOf(TransferProgressSample(20, 1), TransferProgressSample(110, 41)),
            )
        val long =
            ProbeResult(
                "throughput_window",
                "Long response",
                "verified_download_timeout",
                listOf(
                    ProbeDetail("transferVolumeLeg", "long"),
                    ProbeDetail(
                        "transferEvidence",
                        Json.encodeToString(TransferEvidence.serializer(), TransferEvidence(runs = listOf(measure))),
                    ),
                ),
            )
        val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())
        val row = support.toProbeResultUiModel(1, ScanPathMode.RAW_PATH, long)
        val run = requireNotNull(row.transferEvidence).runs.single()
        assertEquals(R.string.diagnostics_volume_transfer_long, row.volumeTransferLegTitleResource())
        assertEquals(41L, run.receivedBodyByteCount)
        assertEquals(20L, run.firstBodyByteMs)
        assertEquals(110L, run.lastBodyProgressMs)
        assertEquals(1_234L, run.elapsedMs)
        val short = long.copy(details = listOf(ProbeDetail("transferVolumeLeg", "short")))
        val plain = long.copy(details = emptyList())
        assertEquals(
            R.string.diagnostics_volume_transfer_short,
            support.toProbeResultUiModel(0, ScanPathMode.RAW_PATH, short).volumeTransferLegTitleResource(),
        )
        assertNull(support.toProbeResultUiModel(0, ScanPathMode.RAW_PATH, plain).volumeTransferLegTitleResource())
    }

    private fun bundledProfile(): DiagnosticProfile {
        val title = "Short and long HTTPS downloads"
        val wire =
            ProfileSpecWire(
                profileId = profileId,
                displayName = title,
                executionPolicy = ProfileExecutionPolicyWire(manualOnly = true, requiresRawPath = true),
            )
        return DiagnosticProfile(
            id = profileId,
            name = title,
            source = "bundled",
            version = 1,
            requestJson = Json.encodeToString(ProfileSpecWire.serializer(), wire),
            updatedAt = 1L,
        )
    }
}
