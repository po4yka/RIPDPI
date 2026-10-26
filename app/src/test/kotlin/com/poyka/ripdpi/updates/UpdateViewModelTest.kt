package com.poyka.ripdpi.updates

import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a check during a download neither replaces its status nor allows a second install`() =
        runTest {
            val manager = GatedUpdateManager()
            val viewModel = UpdateViewModel(manager)
            viewModel.checkForUpdates()
            advanceUntilIdle()

            viewModel.installAvailableUpdate()
            advanceUntilIdle()
            viewModel.checkForUpdates()
            advanceUntilIdle()
            viewModel.installAvailableUpdate()
            advanceUntilIdle()

            assertEquals(UpdateUiStatus.Downloading, viewModel.uiState.value.status)
            assertEquals(1, manager.checks)
            assertEquals(1, manager.installs)

            manager.installRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(UpdateUiStatus.InstallerStarted, viewModel.uiState.value.status)
        }

    private class GatedUpdateManager : AppUpdateManager {
        val installRelease = CompletableDeferred<Unit>()
        var checks = 0
        var installs = 0

        override val channelInfo =
            UpdateChannelInfo(DistributionChannel.Github, canCheckInApp = true, canInstallInApp = true)

        override suspend fun checkForUpdate(): UpdateCheckResult {
            checks += 1
            return UpdateCheckResult.Available(
                AvailableAppUpdate(versionCode = 2, versionName = "2.0", changelog = "", sizeBytes = null),
            )
        }

        override suspend fun install(update: AvailableAppUpdate): UpdateInstallResult {
            installs += 1
            installRelease.await()
            return UpdateInstallResult.Started
        }
    }
}
