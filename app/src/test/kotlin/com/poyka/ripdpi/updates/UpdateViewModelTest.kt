package com.poyka.ripdpi.updates

import android.content.Intent
import android.provider.Settings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun `return from settings keeps a missing grant retryable and never starts installation`() =
        runTest {
            val manager = GatedUpdateManager(installPermissionRequired = true)
            val viewModel = UpdateViewModel(manager)
            viewModel.checkForUpdates()
            advanceUntilIdle()

            viewModel.installAvailableUpdate()
            advanceUntilIdle()
            val availableUpdate = viewModel.uiState.value.availableUpdate

            assertEquals(UpdateUiStatus.PermissionRequired, viewModel.uiState.value.status)
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, viewModel.installPermissionSettings()?.action)

            viewModel.onResume()
            assertEquals(UpdateUiStatus.PermissionRequired, viewModel.uiState.value.status)
            assertEquals(1, manager.installs)

            manager.installPermissionRequired = false
            viewModel.onResume()
            assertEquals(UpdateUiStatus.Idle, viewModel.uiState.value.status)
            assertEquals(availableUpdate, viewModel.uiState.value.availableUpdate)
            assertEquals(1, manager.installs)
        }

    @Test
    fun `return from the installer makes the available update retryable`() =
        runTest {
            val manager = GatedUpdateManager()
            val viewModel = UpdateViewModel(manager)
            viewModel.checkForUpdates()
            advanceUntilIdle()
            manager.installRelease.complete(Unit)
            viewModel.installAvailableUpdate()
            advanceUntilIdle()
            val availableUpdate = viewModel.uiState.value.availableUpdate

            assertEquals(UpdateUiStatus.InstallerStarted, viewModel.uiState.value.status)
            viewModel.onResume()
            assertEquals(UpdateUiStatus.Idle, viewModel.uiState.value.status)
            assertEquals(availableUpdate, viewModel.uiState.value.availableUpdate)
        }

    private class GatedUpdateManager(
        var installPermissionRequired: Boolean = false,
    ) : AppUpdateManager {
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

        override fun installPermissionSettings(): Intent? =
            if (installPermissionRequired) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) else null

        override suspend fun install(update: AvailableAppUpdate): UpdateInstallResult {
            installs += 1
            if (installPermissionRequired) return UpdateInstallResult.PermissionRequired
            installRelease.await()
            return UpdateInstallResult.Started
        }
    }
}
