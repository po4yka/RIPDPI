// Unit test for the Xray import ViewModel's state transitions over the real
// XrayImportParser (additionally unit-tested in :core:data:catalog).
package com.poyka.ripdpi.ui.screens.xray

import com.poyka.ripdpi.data.xray.XrayImportParser
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayProfileImportViewModelTest {
    private val stableTag = "v1.260206.0"
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val pbk = "AbCdEf0123456789AbCdEf0123456789AbCdEf01234"

    private class RecordingPersistence : XrayProfilePersistence {
        override val upstreamTag: String = "v1.260206.0"
        override val emptyInputMessage: String = "empty"
        var persisted: Pair<XrayServiceModeOption, XrayProfile?>? = null

        override fun persist(
            option: XrayServiceModeOption,
            profile: XrayProfile?,
        ) {
            persisted = option to profile
        }
    }

    private fun viewModel(persistence: RecordingPersistence = RecordingPersistence()) =
        XrayProfileImportViewModel(XrayImportParser(), persistence)

    @Test
    fun selectingXrayOptionRequiresProfileAndBlocksFinish() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        assertTrue(vm.uiState.value.requiresXrayProfile)
        assertFalse(vm.uiState.value.canFinish)
    }

    @Test
    fun validationFailureSetsRedactedErrorAndNoProfile() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        vm.onRawInputChange("ss://nope")
        vm.validate(stableTag)
        val state = vm.uiState.value
        assertNull(state.accepted)
        assertFalse(state.acceptedConfigReady)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun successfulImportAcceptsProfileAndPersistsOnConfirm() {
        val persistence = RecordingPersistence()
        val vm = viewModel(persistence)
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        val link =
            "vless://$uuid@edge.example.com:443" +
                "?security=reality&pbk=$pbk&sni=www.cloudflare.com&flow=xtls-rprx-vision#n"
        vm.onRawInputChange(link)
        vm.validate(stableTag)

        val accepted = vm.uiState.value
        assertNotNull(accepted.accepted)
        assertTrue(accepted.acceptedConfigReady)
        assertTrue(accepted.capabilities.isNotEmpty())
        assertTrue(accepted.canFinish)

        vm.confirm()
        assertTrue(vm.uiState.value.imported)
        assertEquals(XrayServiceModeOption.XrayVpn, persistence.persisted?.first)
        assertNotNull(persistence.persisted?.second)
    }

    @Test
    fun nativeOptionFinishesWithoutProfile() {
        val persistence = RecordingPersistence()
        val vm = viewModel(persistence)
        vm.selectOption(XrayServiceModeOption.NativeDirect)
        assertTrue(vm.uiState.value.canFinish)
        vm.confirm()
        assertTrue(vm.uiState.value.imported)
        assertEquals(XrayServiceModeOption.NativeDirect, persistence.persisted?.first)
        assertNull(persistence.persisted?.second)
    }
}
