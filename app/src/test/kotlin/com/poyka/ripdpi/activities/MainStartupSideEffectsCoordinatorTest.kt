package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.crash.CrashReport
import com.poyka.ripdpi.diagnostics.crash.CrashReportReader
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MainStartupSideEffectsCoordinatorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `start loads pending crash report`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val crashFile =
                tempFolder
                    .newFolder("crash-reports")
                    .resolve("crash_latest.json")
            crashFile.writeText("{}", Charsets.UTF_8)
            val reader = CrashReportReader(tempFolder.root)
            val coordinator = MainStartupSideEffectsCoordinator(repository, reader)
            val batteryOptimizationStatus = MutableStateFlow(PermissionStatus.Granted)
            val loadedReport = CompletableDeferred<CrashReport>()

            coordinator.start(
                scope = backgroundScope,
                batteryOptimizationStatus = batteryOptimizationStatus,
                isBatteryBannerDismissed = { false },
            ) { report ->
                loadedReport.complete(report)
            }

            val report = loadedReport.await()

            assertEquals("", report.exceptionClass)
        }

    @Test
    fun `start clears dismissed battery banner when optimization becomes required`() =
        runTest {
            val repository =
                FakeAppSettingsRepository(
                    AppSettings
                        .newBuilder()
                        .setBatteryBannerDismissed(true)
                        .build(),
                )
            val reader = CrashReportReader(tempFolder.root)
            val coordinator = MainStartupSideEffectsCoordinator(repository, reader)

            coordinator.start(
                scope = this,
                batteryOptimizationStatus =
                    flowOf(
                        PermissionStatus.Granted,
                        PermissionStatus.RequiresSettings,
                    ),
                isBatteryBannerDismissed = { true },
            ) {}

            advanceUntilIdle()

            assertFalse(repository.snapshot().batteryBannerDismissed)
        }

    @Test
    fun `start ignores missing crash report`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val reader = CrashReportReader(tempFolder.root)
            val coordinator = MainStartupSideEffectsCoordinator(repository, reader)
            var callbackCalls = 0

            coordinator.start(
                scope = backgroundScope,
                batteryOptimizationStatus = MutableStateFlow(PermissionStatus.Granted),
                isBatteryBannerDismissed = { false },
            ) {
                callbackCalls += 1
            }

            advanceUntilIdle()

            assertEquals(0, callbackCalls)
        }

    @Test
    fun `repeated requires settings emissions clear battery banner only once`() =
        runTest {
            val repository =
                FakeAppSettingsRepository(
                    AppSettings
                        .newBuilder()
                        .setBatteryBannerDismissed(true)
                        .build(),
                )
            val reader = CrashReportReader(tempFolder.root)
            val coordinator = MainStartupSideEffectsCoordinator(repository, reader)
            val statuses = MutableSharedFlow<PermissionStatus>(extraBufferCapacity = 4)

            coordinator.start(
                scope = this,
                batteryOptimizationStatus = statuses,
                isBatteryBannerDismissed = { true },
            ) {}

            statuses.emit(PermissionStatus.RequiresSettings)
            statuses.emit(PermissionStatus.RequiresSettings)
            advanceUntilIdle()

            assertFalse(repository.snapshot().batteryBannerDismissed)
        }
}
