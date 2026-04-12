package com.poyka.ripdpi.activities

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainSettingsDismissCoordinatorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `dismissBatteryBanner persists dismissal`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val coordinator = MainSettingsDismissCoordinator(repository)

            coordinator.dismissBatteryBanner(this)
            advanceUntilIdle()

            assertTrue(repository.snapshot().batteryBannerDismissed)
        }

    @Test
    fun `dismissBackgroundGuidance persists dismissal`() =
        runTest {
            val repository =
                FakeAppSettingsRepository(
                    AppSettings.newBuilder().setBackgroundGuidanceDismissed(false).build(),
                )
            val coordinator = MainSettingsDismissCoordinator(repository)

            coordinator.dismissBackgroundGuidance(this)
            advanceUntilIdle()

            assertTrue(repository.snapshot().backgroundGuidanceDismissed)
        }
}
