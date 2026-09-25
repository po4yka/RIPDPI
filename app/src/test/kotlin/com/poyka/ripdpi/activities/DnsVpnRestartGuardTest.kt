package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DnsVpnRestartGuardTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val doqSettings =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setDnsMode(DnsModeEncrypted)
            .setEncryptedDnsProtocol(EncryptedDnsProtocolDoq)
            .build()

    @Test
    fun `config save preserves running service when vpn doq cannot restart`() =
        runTest {
            val controller = FakeServiceController()
            var rejected = 0

            applySavedConfigDraftToRunningService(
                draft = ConfigDraft(mode = Mode.VPN),
                appSettingsRepository = FakeAppSettingsRepository(doqSettings),
                serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                serviceController = controller,
                startRuntimeMode = { error("Unsupported VPN restart") },
                onUnsupportedVpnDns = { rejected++ },
            )

            assertEquals(1, rejected)
            assertEquals(0, controller.stopCount)
            assertTrue(controller.startedModes.isEmpty())
        }

    @Test
    fun `strategy save reports unsupported vpn dns before stopping service`() =
        runTest {
            val controller = FakeServiceController()
            var rejected = 0
            val actions =
                MainStrategyConfigApplyActions(
                    scope = this,
                    appSettingsRepository = FakeAppSettingsRepository(doqSettings),
                    currentSettings = { doqSettings },
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    serviceController = controller,
                    onUnsupportedVpnDns = { rejected++ },
                )

            assertEquals(StrategyConfigApplyResult.UnsupportedVpnDns, actions.applySavedStrategyConfig())
            assertEquals(0, rejected)
            assertEquals(0, controller.stopCount)
        }

    @Test
    fun `strategy restart rechecks persisted dns before stopping service`() =
        runTest {
            val controller = FakeServiceController()
            var rejected = 0
            val actions =
                MainStrategyConfigApplyActions(
                    scope = this,
                    appSettingsRepository = FakeAppSettingsRepository(doqSettings),
                    currentSettings = { AppSettingsSerializer.defaultValue },
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    serviceController = controller,
                    onUnsupportedVpnDns = { rejected++ },
                )

            assertEquals(StrategyConfigApplyResult.RestartingActiveService, actions.applySavedStrategyConfig())
            runCurrent()
            assertEquals(1, rejected)
            assertEquals(0, controller.stopCount)
        }
}
