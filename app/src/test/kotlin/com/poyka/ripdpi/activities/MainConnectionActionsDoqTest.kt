package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainConnectionActionsDoqTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `vpn start with saved doq is rejected before service start`() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModeEncrypted)
                    .setEncryptedDnsProtocol(EncryptedDnsProtocolDoq)
                    .build()
            val effects = MutableSharedFlow<MainEffect>(replay = 1)
            val controller = FakeServiceController()
            val state = MutableStateFlow(ConnectionRuntimeState())
            val actions =
                MainConnectionActions(
                    mutations = MainMutationRunner(this, effects) { MainUiState() },
                    serviceController = controller,
                    serviceStateStore = FakeServiceStateStore(),
                    trafficStatsReader = FakeTrafficStatsReader(),
                    stringResolver = FakeStringResolver(),
                    currentSettings = { settings },
                    runtimeState = ConnectionRuntimeStateReducer(state),
                    refreshPermissionSnapshot = {},
                )

            actions.startMode(Mode.VPN)

            assertTrue(controller.startedModes.isEmpty())
            assertEquals(ConnectionState.Disconnected, state.value.connectionState)
            assertEquals(
                R.string.dns_custom_doq_unavailable.toString(),
                (effects.replayCache.single() as MainEffect.ShowError).message,
            )

            actions.startMode(Mode.Proxy)
            assertEquals(listOf(Mode.Proxy), controller.startedModes)
        }
}
