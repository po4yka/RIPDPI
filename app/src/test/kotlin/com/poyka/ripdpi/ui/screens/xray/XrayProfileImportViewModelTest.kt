// Unit test for the Xray import ViewModel's state transitions over the real
// XrayConfigImportParser (additionally unit-tested in :core:data).
package com.poyka.ripdpi.ui.screens.xray

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.XraySkipReason
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XrayProfileImportViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val uuid2 = "660e8400-e29b-41d4-a716-446655440111"
    private val pbk = "AbCdEf0123456789AbCdEf0123456789AbCdEf01234"

    private class RecordingPersistence : XrayProfilePersistence {
        override val emptyInputMessage: String = "empty"
        override val noSupportedNodesMessage: String = "no-supported"
        override val unparseableMessage: String = "unparseable"
        var persisted: Pair<XrayServiceModeOption, List<ProxyProfile>>? = null

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
        ) {
            persisted = option to profiles
        }
    }

    private fun viewModel(persistence: RecordingPersistence = RecordingPersistence()) =
        XrayProfileImportViewModel(persistence)

    @Test
    fun selectingXrayOptionRequiresProfileAndBlocksFinish() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        assertTrue(vm.uiState.value.requiresXrayProfile)
        assertFalse(vm.uiState.value.canFinish)
    }

    @Test
    fun unsupportedOnlyInputSurfacesSkipReasonAndBlocksFinish() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        // Plain VLESS (no REALITY) is unsupported natively → translated to a skip.
        vm.onRawInputChange("vless://$uuid@host.example:443#node")
        vm.validate()
        val state = vm.uiState.value
        assertFalse(state.acceptedConfigReady)
        assertEquals(0, state.importableCount)
        assertTrue(state.skipped.isNotEmpty())
        assertNotNull(state.errorMessage)
    }

    @Test
    fun unrecognisedInputSetsUnparseableError() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        vm.onRawInputChange("this is not a config")
        vm.validate()
        val state = vm.uiState.value
        assertFalse(state.acceptedConfigReady)
        assertEquals("unparseable", state.errorMessage)
        assertTrue(state.skipped.isEmpty())
    }

    @Test
    fun successfulImportTranslatesProfileAndPersistsOnConfirm() =
        runTest {
            val persistence = RecordingPersistence()
            val vm = viewModel(persistence)
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            val link =
                "vless://$uuid@edge.example.com:443" +
                    "?security=reality&pbk=$pbk&sni=www.cloudflare.com&flow=xtls-rprx-vision#n"
            vm.onRawInputChange(link)
            vm.validate()

            val accepted = vm.uiState.value
            assertTrue(accepted.acceptedConfigReady)
            assertEquals(1, accepted.importableCount)
            assertTrue(accepted.capabilities.isNotEmpty())
            assertTrue(accepted.canFinish)

            vm.confirm()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.imported)
            assertEquals(XrayServiceModeOption.XrayVpn, persistence.persisted?.first)
            assertEquals(1, persistence.persisted?.second?.size)
            assertTrue(persistence.persisted?.second?.first() is ProxyProfile.VlessReality)
        }

    @Test
    fun multiOutboundJsonImportsSupportedAndSkipsVmess() =
        runTest {
            val persistence = RecordingPersistence()
            val vm = viewModel(persistence)
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            val config =
                """
                {
                  "outbounds": [
                    {
                      "tag": "reality",
                      "protocol": "vless",
                      "settings": { "vnext": [ { "address": "edge.example.com", "port": 443,
                        "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
                      "streamSettings": { "network": "tcp", "security": "reality",
                        "realitySettings": { "publicKey": "$pbk", "serverName": "www.cloudflare.com", "shortId": "ab12" } }
                    },
                    { "tag": "legacy", "protocol": "vmess", "settings": { "vnext": [] } },
                    { "tag": "direct", "protocol": "freedom" }
                  ]
                }
                """.trimIndent()
            vm.onRawInputChange(config)
            vm.validate()

            val state = vm.uiState.value
            assertTrue(state.acceptedConfigReady)
            assertEquals(1, state.importableCount)
            // vmess (removed) and freedom (utility) are both surfaced as skips.
            assertEquals(2, state.skipped.size)

            vm.confirm()
            advanceUntilIdle()
            assertEquals(1, persistence.persisted?.second?.size)
        }

    @Test
    fun multipleSupportedActivatesFirstAndDefersRest() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        val links =
            "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=h#first\n" +
                "trojan://pw@tj.example:443#second\n" +
                "vless://$uuid2@h2.example:443#plain"
        vm.onRawInputChange(links)
        vm.validate()

        val state = vm.uiState.value
        // Two supported (reality + trojan) → first activated, second deferred.
        assertEquals(1, state.importableCount)
        // plain vless (reality-required) + the deferred trojan are both surfaced.
        assertTrue(state.skipped.any { it.reason == XraySkipReason.SINGLE_RELAY_ONLY })
        assertTrue(state.skipped.any { it.reason == XraySkipReason.VLESS_REQUIRES_REALITY })
    }

    @Test
    fun nativeOptionFinishesWithoutProfile() =
        runTest {
            val persistence = RecordingPersistence()
            val vm = viewModel(persistence)
            vm.selectOption(XrayServiceModeOption.NativeDirect)
            assertTrue(vm.uiState.value.canFinish)
            vm.confirm()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.imported)
            assertEquals(XrayServiceModeOption.NativeDirect, persistence.persisted?.first)
            assertTrue(persistence.persisted?.second?.isEmpty() == true)
        }
}
