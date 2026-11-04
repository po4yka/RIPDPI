package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainConnectionActionsPassageTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `an external provider leaves no scene`() =
        runTest {
            val settings = AppSettingsSerializer.defaultValue
            val actions = { hasProvider: Boolean ->
                MainConnectionActions(
                    mutations = MainMutationRunner(this, MutableSharedFlow<MainEffect>()) { MainUiState() },
                    serviceController = FakeServiceController(),
                    serviceStateStore = FakeServiceStateStore(),
                    trafficStatsReader = FakeTrafficStatsReader(),
                    stringResolver = FakeStringResolver(),
                    currentSettings = { settings },
                    runtimeState = ConnectionRuntimeStateReducer(MutableStateFlow(ConnectionRuntimeState())),
                    refreshPermissionSnapshot = {},
                    hasExternalProvider = { hasProvider },
                )
            }

            assertNotNull(actions(false).buildApproachSummary(settings, Mode.VPN, emptyList()).passage)
            assertNull(actions(true).buildApproachSummary(settings, Mode.VPN, emptyList()).passage)
        }
}
