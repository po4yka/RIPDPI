package com.poyka.ripdpi.ui.screens.xray

import app.cash.turbine.test
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.subscription.MaxSubscriptionPayloadBytes
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.ui.screens.xray.XrayImportViewModelFixtures.FlakyPersistence
import com.poyka.ripdpi.ui.screens.xray.XrayImportViewModelFixtures.RecordingPersistence
import com.poyka.ripdpi.ui.screens.xray.XrayImportViewModelFixtures.testDispatchers
import com.poyka.ripdpi.ui.screens.xray.XrayImportViewModelFixtures.viewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.poyka.ripdpi.ui.screens.xray.XrayImportViewModelFixtures.PUBLIC_KEY as pbk
import com.poyka.ripdpi.ui.screens.xray.XrayImportViewModelFixtures.UUID as uuid

@OptIn(ExperimentalCoroutinesApi::class)
class XrayProfileImportAdmissionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `manual byte budget has a size error for native and typed providers`() {
        for (option in listOf(XrayServiceModeOption.NativeProxy, XrayServiceModeOption.XrayVpn)) {
            val vm = viewModel()
            vm.selectOption(option)
            vm.onRawInputChange("x".repeat(MaxSubscriptionPayloadBytes.toInt() + 1))
            vm.validate()

            assertEquals("too-large", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.acceptedConfigReady)
            assertFalse(vm.uiState.value.validating)
        }
    }

    @Test
    fun `original padded input obeys the byte budget before trimming`() {
        val padding = "\u2000".repeat(MaxSubscriptionPayloadBytes.toInt() / 3)
        val inputs =
            listOf(
                XrayServiceModeOption.NativeProxy to "trojan://fixture@t.example:443#node",
                XrayServiceModeOption.XrayVpn to
                    "vless://$uuid@edge.example.com:443" +
                    "?security=reality&pbk=$pbk&sni=www.cloudflare.com&flow=xtls-rprx-vision#n",
            )
        for ((option, input) in inputs) {
            val vm = viewModel()
            vm.selectOption(option)
            vm.onRawInputChange(padding + input)
            vm.validate()

            assertEquals("too-large", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.acceptedConfigReady)
        }
    }

    @Test
    fun `typed provider budget counts UTF-8 bytes`() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        vm.onRawInputChange("я".repeat(MaxSubscriptionPayloadBytes.toInt() / 2 + 1))
        vm.validate()

        assertEquals("too-large", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.canFinish)
    }

    @Test
    fun `manual node budget has a size error for both providers`() {
        for (option in listOf(XrayServiceModeOption.NativeProxy, XrayServiceModeOption.XrayVpn)) {
            val vm = viewModel()
            vm.selectOption(option)
            val node = """{"protocol":"shadowsocks","settings":{"address":"ss.example","port":443,
                "method":"aes-128-gcm","password":"fixture"}}"""
            vm.onRawInputChange(List(2_049) { node }.joinToString(prefix = "{\"outbounds\":[", postfix = "]}"))
            vm.validate()

            assertEquals("too-large", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.canFinish)
        }
    }

    @Test
    fun `oversized blank native input cannot finish before or after validation and provider changes`() =
        runTest {
            val inputs =
                listOf(
                    " ".repeat(MaxSubscriptionPayloadBytes.toInt() + 1),
                    "\u2000".repeat((MaxSubscriptionPayloadBytes / 3).toInt() + 1),
                )
            for (input in inputs) {
                for (option in listOf(XrayServiceModeOption.NativeProxy, XrayServiceModeOption.NativeDirect)) {
                    val vm = XrayProfileImportViewModel(RecordingPersistence(), testDispatchers())
                    vm.selectOption(option)
                    vm.onRawInputChange(input)
                    assertFalse(vm.uiState.value.canFinish)
                    vm.importedEvents.test {
                        vm.confirm()
                        expectNoEvents()
                        vm.validate()
                        assertEquals("too-large", vm.uiState.value.errorMessage)
                        assertFalse(vm.uiState.value.canFinish)
                        vm.confirm()
                        expectNoEvents()
                        vm.selectOption(
                            if (option == XrayServiceModeOption.NativeProxy) {
                                XrayServiceModeOption.NativeDirect
                            } else {
                                XrayServiceModeOption.NativeProxy
                            },
                        )
                        assertEquals(input, vm.uiState.value.rawInput)
                        assertFalse(vm.uiState.value.canFinish)
                        vm.confirm()
                        expectNoEvents()
                    }
                }
            }
        }

    @Test
    fun `blank input byte admission runs off main and ignores older input results`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val parserScheduler = TestCoroutineScheduler()
            val parserDispatcher = StandardTestDispatcher(parserScheduler)
            val vm =
                XrayProfileImportViewModel(
                    RecordingPersistence(),
                    AppCoroutineDispatchers(parserDispatcher, parserDispatcher, Dispatchers.Main),
                )
            runCurrent()
            vm.selectOption(XrayServiceModeOption.NativeDirect)
            vm.onRawInputChange(" \t")
            runCurrent()
            assertFalse(vm.uiState.value.canFinish)
            parserScheduler.runCurrent()
            vm.onRawInputChange("\u2000".repeat((MaxSubscriptionPayloadBytes / 3).toInt() + 1))
            runCurrent()
            assertFalse(vm.uiState.value.canFinish)
            parserScheduler.runCurrent()
            runCurrent()
            assertFalse(vm.uiState.value.canFinish)

            vm.onRawInputChange(" \t")
            runCurrent()
            assertFalse(vm.uiState.value.canFinish)
            parserScheduler.runCurrent()
            runCurrent()
            assertTrue(vm.uiState.value.canFinish)
        }

    @Test
    fun `ordinary native whitespace can finish and retry after persistence fails`() =
        runTest {
            val vm = XrayProfileImportViewModel(FlakyPersistence(), testDispatchers())
            vm.selectOption(XrayServiceModeOption.NativeDirect)
            vm.onRawInputChange(" \u2000\t")
            assertTrue(vm.uiState.value.canFinish)
            vm.importedEvents.test {
                vm.confirm()
                advanceUntilIdle()
                assertEquals("activation-failed", vm.uiState.value.errorMessage)
                assertTrue(vm.uiState.value.canFinish)
                expectNoEvents()
                vm.confirm()
                advanceUntilIdle()
                awaitItem()
            }
        }

    @Test
    fun `validation runs on the parser dispatcher and ignores a late result for old input`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val parserScheduler = TestCoroutineScheduler()
            val parserDispatcher = StandardTestDispatcher(parserScheduler)
            val vm =
                XrayProfileImportViewModel(
                    RecordingPersistence(),
                    AppCoroutineDispatchers(parserDispatcher, parserDispatcher, Dispatchers.Main),
                )
            runCurrent()
            vm.selectOption(XrayServiceModeOption.NativeProxy)
            vm.onRawInputChange("trojan://fixture@old.example:443#old")
            vm.validate()
            runCurrent()

            assertTrue(vm.uiState.value.validating)
            assertFalse(vm.uiState.value.canFinish)
            parserScheduler.runCurrent()
            vm.onRawInputChange("new invalid input")
            vm.validate()
            runCurrent()
            assertFalse(vm.uiState.value.acceptedConfigReady)
            assertTrue(vm.uiState.value.validating)
            assertEquals("new invalid input", vm.uiState.value.rawInput)

            parserScheduler.runCurrent()
            runCurrent()
            assertEquals("unparseable", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.acceptedConfigReady)
        }

    @Test
    fun `changing provider discards late typed results and their private cache`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val parserScheduler = TestCoroutineScheduler()
            val parserDispatcher = StandardTestDispatcher(parserScheduler)
            val vm =
                XrayProfileImportViewModel(
                    RecordingPersistence(),
                    AppCoroutineDispatchers(parserDispatcher, parserDispatcher, Dispatchers.Main),
                )
            runCurrent()
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            vm.onRawInputChange(
                "vless://$uuid@edge.example.com:443" +
                    "?security=reality&pbk=$pbk&sni=www.cloudflare.com&flow=xtls-rprx-vision#n",
            )
            vm.validate()
            runCurrent()
            assertTrue(vm.uiState.value.validating)
            parserScheduler.runCurrent()
            vm.selectOption(XrayServiceModeOption.NativeProxy)
            runCurrent()

            assertEquals(XrayServiceModeOption.NativeProxy, vm.uiState.value.selectedOption)
            assertFalse(vm.uiState.value.acceptedConfigReady)
            assertFalse(vm.uiState.value.validating)
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            assertFalse(vm.uiState.value.acceptedConfigReady)
        }
}
