// Unit test for the Xray import ViewModel's state transitions over the real
// XrayConfigImportParser (additionally unit-tested in :core:data).
package com.poyka.ripdpi.ui.screens.xray

import app.cash.turbine.test
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.XraySkipReason
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XrayProfileImportViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val uuid2 = "660e8400-e29b-41d4-a716-446655440111"
    private val pbk = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    private class RecordingPersistence : XrayProfilePersistence {
        override val emptyInputMessage: String = "empty"
        override val noSupportedNodesMessage: String = "no-supported"
        override val unparseableMessage: String = "unparseable"
        override val persistFailedMessage: String = "activation-failed"
        override val restoreFailedMessage: String = "restore-failed"
        override val unsupportedTypedProfileMessage: String = "typed-unsupported"
        override val missingTypedProfileFieldMessage: String = "typed-missing"
        override val unsafeTypedProfileMessage: String = "typed-unsafe"
        var failNextRestore = false
        var restored: XrayProviderSelection = XrayProviderSelection()
        var persisted: Triple<XrayServiceModeOption, List<ProxyProfile>, XrayProfile?>? = null

        override suspend fun restoreSelection(): XrayProviderSelection {
            if (failNextRestore) {
                failNextRestore = false
                error("durable read failed")
            }
            return restored
        }

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
            acceptedProfile: XrayProfile?,
        ) {
            persisted = Triple(option, profiles, acceptedProfile)
        }
    }

    /** Persistence that throws on the first call, then succeeds — exercises the retry path. */
    private class FlakyPersistence : XrayProfilePersistence {
        override val emptyInputMessage: String = "empty"
        override val noSupportedNodesMessage: String = "no-supported"
        override val unparseableMessage: String = "unparseable"
        override val persistFailedMessage: String = "activation-failed"
        override val restoreFailedMessage: String = "restore-failed"
        override val unsupportedTypedProfileMessage: String = "typed-unsupported"
        override val missingTypedProfileFieldMessage: String = "typed-missing"
        override val unsafeTypedProfileMessage: String = "typed-unsafe"
        var attempts: Int = 0

        override suspend fun restoreSelection(): XrayProviderSelection = XrayProviderSelection()

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
            acceptedProfile: XrayProfile?,
        ) {
            attempts += 1
            if (attempts == 1) error("store write failed")
        }
    }

    private class FailingRestorePersistence : XrayProfilePersistence {
        override val emptyInputMessage: String = "empty"
        override val noSupportedNodesMessage: String = "no-supported"
        override val unparseableMessage: String = "unparseable"
        override val persistFailedMessage: String = "activation-failed"
        override val restoreFailedMessage: String = "restore-failed"
        override val unsupportedTypedProfileMessage: String = "typed-unsupported"
        override val missingTypedProfileFieldMessage: String = "typed-missing"
        override val unsafeTypedProfileMessage: String = "typed-unsafe"

        override suspend fun restoreSelection(): XrayProviderSelection = error("durable read failed")

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
            acceptedProfile: XrayProfile?,
        ) = Unit
    }

    private class DeferredRestorePersistence : XrayProfilePersistence {
        override val emptyInputMessage: String = "empty"
        override val noSupportedNodesMessage: String = "no-supported"
        override val unparseableMessage: String = "unparseable"
        override val persistFailedMessage: String = "activation-failed"
        override val restoreFailedMessage: String = "restore-failed"
        override val unsupportedTypedProfileMessage: String = "typed-unsupported"
        override val missingTypedProfileFieldMessage: String = "typed-missing"
        override val unsafeTypedProfileMessage: String = "typed-unsafe"
        val restored = CompletableDeferred<XrayProviderSelection>()

        override suspend fun restoreSelection(): XrayProviderSelection = restored.await()

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
            acceptedProfile: XrayProfile?,
        ) = Unit
    }

    private class SuspendedPersistPersistence : XrayProfilePersistence {
        override val emptyInputMessage: String = "empty"
        override val noSupportedNodesMessage: String = "no-supported"
        override val unparseableMessage: String = "unparseable"
        override val persistFailedMessage: String = "activation-failed"
        override val restoreFailedMessage: String = "restore-failed"
        override val unsupportedTypedProfileMessage: String = "typed-unsupported"
        override val missingTypedProfileFieldMessage: String = "typed-missing"
        override val unsafeTypedProfileMessage: String = "typed-unsafe"
        val persistGate = CompletableDeferred<Unit>()
        var persisted: Triple<XrayServiceModeOption, List<ProxyProfile>, XrayProfile?>? = null

        override suspend fun restoreSelection(): XrayProviderSelection = XrayProviderSelection()

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
            acceptedProfile: XrayProfile?,
        ) {
            persisted = Triple(option, profiles, acceptedProfile)
            persistGate.await()
        }
    }

    private fun viewModel(persistence: XrayProfilePersistence = RecordingPersistence()) =
        XrayProfileImportViewModel(persistence)

    private fun xrayProfile(): XrayProfile =
        XrayProfile(
            name = "restored",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = "edge.example.com",
                    serverPort = 443,
                    uuid = uuid,
                    flow = "xtls-rprx-vision",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = pbk,
                            serverName = "www.cloudflare.com",
                            shortId = "ab12",
                            fingerprint = "chrome",
                        ),
                ),
            inbound = XrayProfile.LocalInbound(port = 10808),
        )

    @Test
    fun selectingXrayOptionRequiresProfileAndBlocksFinish() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        assertTrue(vm.uiState.value.requiresXrayProfile)
        assertFalse(vm.uiState.value.canFinish)
    }

    @Test
    fun `recreated view model restores durable xray selection as ready`() =
        runTest {
            val persistence =
                RecordingPersistence().apply {
                    restored =
                        XrayProviderSelection(
                            option = XrayServiceModeOption.XrayVpn,
                            acceptedProfile = xrayProfile(),
                        )
                }

            val vm = viewModel(persistence)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(XrayServiceModeOption.XrayVpn, state.selectedOption)
            assertEquals("", state.rawInput)
            assertTrue(state.acceptedConfigReady)
            assertTrue(state.canFinish)
            assertTrue(state.capabilities.isNotEmpty())
        }

    @Test
    fun `recreated view model keeps xray selection blocked when durable profile is missing`() =
        runTest {
            val persistence =
                RecordingPersistence().apply {
                    restored =
                        XrayProviderSelection(
                            option = XrayServiceModeOption.XrayVpn,
                            acceptedProfile = null,
                        )
                }

            val vm = viewModel(persistence)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(XrayServiceModeOption.XrayVpn, state.selectedOption)
            assertTrue(state.requiresXrayProfile)
            assertFalse(state.acceptedConfigReady)
            assertFalse(state.canFinish)
            assertTrue(state.capabilities.isEmpty())
        }

    @Test
    fun `restore failure blocks finish until retry succeeds`() =
        runTest {
            val vm = XrayProfileImportViewModel(FailingRestorePersistence())
            advanceUntilIdle()

            val failed = vm.uiState.value
            assertEquals(XrayImportRestoreStatus.Failed, failed.restoreStatus)
            assertEquals("restore-failed", failed.restoreErrorMessage)
            assertFalse(failed.acceptedConfigReady)
            assertFalse(failed.canFinish)

            vm.confirm()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.canFinish)
        }

    @Test
    fun `retry restore preserves validated input after initial restore failure`() =
        runTest {
            val persistence =
                RecordingPersistence().apply {
                    failNextRestore = true
                    restored = XrayProviderSelection(XrayServiceModeOption.XrayVpn, xrayProfile())
                }
            val vm = XrayProfileImportViewModel(persistence)
            advanceUntilIdle()
            assertEquals(XrayImportRestoreStatus.Failed, vm.uiState.value.restoreStatus)
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            val input =
                "vless://$uuid2@edge.example.com:443" +
                    "?security=tls&sni=fixture.test&flow=xtls-rprx-vision&type=tcp#edited"
            vm.onRawInputChange(input)
            vm.validate()
            assertTrue(vm.uiState.value.acceptedConfigReady)

            vm.retryRestore()
            advanceUntilIdle()
            assertEquals(input, vm.uiState.value.rawInput)
            assertTrue(vm.uiState.value.canFinish)
            vm.confirm()
            advanceUntilIdle()

            assertEquals(
                uuid2,
                persistence.persisted
                    ?.third
                    ?.outbound
                    ?.uuid,
            )
        }

    @Test
    fun `initial restore loading blocks native finish`() =
        runTest {
            val persistence = DeferredRestorePersistence()
            val vm = XrayProfileImportViewModel(persistence)

            val loading = vm.uiState.value
            assertEquals(XrayImportRestoreStatus.Loading, loading.restoreStatus)
            assertFalse(loading.canFinish)

            persistence.restored.complete(XrayProviderSelection(option = XrayServiceModeOption.NativeDirect))
            advanceUntilIdle()
            assertEquals(XrayImportRestoreStatus.Ready, vm.uiState.value.restoreStatus)
            assertTrue(vm.uiState.value.canFinish)
        }

    @Test
    fun `late durable restore does not overwrite user selection`() =
        runTest {
            val persistence = DeferredRestorePersistence()
            val vm = XrayProfileImportViewModel(persistence)

            vm.selectOption(XrayServiceModeOption.NativeProxy)
            persistence.restored.complete(
                XrayProviderSelection(
                    option = XrayServiceModeOption.XrayVpn,
                    acceptedProfile = xrayProfile(),
                ),
            )
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(XrayServiceModeOption.NativeProxy, state.selectedOption)
            assertFalse(state.acceptedConfigReady)
            assertTrue(state.canFinish)

            vm.selectOption(XrayServiceModeOption.XrayVpn)
            val xrayState = vm.uiState.value
            assertEquals(XrayServiceModeOption.XrayVpn, xrayState.selectedOption)
            assertFalse(xrayState.acceptedConfigReady)
            assertFalse(xrayState.canFinish)
        }

    @Test
    fun `native restore keeps stored xray profile available when switching back`() =
        runTest {
            val persistence =
                RecordingPersistence().apply {
                    restored =
                        XrayProviderSelection(
                            option = XrayServiceModeOption.NativeProxy,
                            acceptedProfile = null,
                            storedXrayProfile = xrayProfile(),
                        )
                }
            val vm = viewModel(persistence)
            advanceUntilIdle()

            assertEquals(XrayServiceModeOption.NativeProxy, vm.uiState.value.selectedOption)
            assertTrue(vm.uiState.value.canFinish)

            vm.onRawInputChange("user pasted newer text")
            vm.selectOption(XrayServiceModeOption.XrayVpn)

            val state = vm.uiState.value
            assertEquals(XrayServiceModeOption.XrayVpn, state.selectedOption)
            assertEquals("", state.rawInput)
            assertTrue(state.acceptedConfigReady)
            assertTrue(state.canFinish)
            assertTrue(state.capabilities.isNotEmpty())
        }

    @Test
    fun `xray validation uses typed parser before native translator`() =
        runTest {
            val persistence = RecordingPersistence()
            val vm = viewModel(persistence)
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            // Typed libXray accepts concrete TLS/TCP/Vision; native relay translator
            // rejects the same input because native Vision is restricted to REALITY.
            vm.onRawInputChange(
                "vless://$uuid@edge.example.com:443" +
                    "?security=tls&sni=www.cloudflare.com&flow=xtls-rprx-vision&type=tcp#tls",
            )
            vm.validate()

            val state = vm.uiState.value
            assertTrue(state.acceptedConfigReady)
            assertTrue(state.canFinish)
            assertEquals(1, state.importableCount)

            vm.importedEvents.test {
                vm.confirm()
                advanceUntilIdle()
                awaitItem()
            }
            assertEquals(XrayServiceModeOption.XrayVpn, persistence.persisted?.first)
            assertNull(persistence.persisted?.second?.firstOrNull())
            assertEquals(
                XrayProfile.Security.TLS,
                persistence.persisted
                    ?.third
                    ?.outbound
                    ?.security,
            )
        }

    @Test
    fun `editing during suspended persist does not replace confirmed payload`() =
        runTest {
            val persistence = SuspendedPersistPersistence()
            val vm = XrayProfileImportViewModel(persistence)
            advanceUntilIdle()
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            vm.onRawInputChange(
                "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=h#n",
            )
            vm.validate()

            vm.confirm()
            vm.selectOption(XrayServiceModeOption.NativeProxy)
            advanceUntilIdle()

            assertEquals(XrayServiceModeOption.XrayVpn, persistence.persisted?.first)
            assertNotNull(persistence.persisted?.third)
            assertEquals(XrayServiceModeOption.XrayVpn, vm.uiState.value.selectedOption)
            assertFalse(vm.uiState.value.canFinish)

            persistence.persistGate.complete(Unit)
            vm.importedEvents.test {
                advanceUntilIdle()
                awaitItem()
            }
        }

    @Test
    fun unsupportedOnlyInputSurfacesSkipReasonAndBlocksFinish() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        // Plain VLESS is rejected by the typed Xray parser before native translation.
        vm.onRawInputChange("vless://$uuid@host.example:443#node")
        vm.validate()
        val state = vm.uiState.value
        assertFalse(state.acceptedConfigReady)
        assertEquals(0, state.importableCount)
        assertTrue(state.skipped.isEmpty())
        assertEquals("typed-unsupported", state.errorMessage)
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

            vm.importedEvents.test {
                vm.confirm()
                advanceUntilIdle()
                awaitItem()
                expectNoEvents()
            }
            assertEquals(XrayServiceModeOption.XrayVpn, persistence.persisted?.first)
            assertTrue(persistence.persisted?.second?.isEmpty() == true)
            // The typed Xray profile is threaded through for the REALITY outbound so the
            // libXray runner has a production source.
            val acceptedProfile = persistence.persisted?.third
            assertNotNull(acceptedProfile)
            assertEquals(XrayProfile.Security.REALITY, acceptedProfile?.outbound?.security)
            assertEquals(uuid, acceptedProfile?.outbound?.uuid)
            assertEquals(pbk, acceptedProfile?.outbound?.reality?.publicKey)
            // The persisted profile carries a stable, user-meaningful label (not the
            // relay displayName / share-link fragment "#n").
            assertEquals("Imported Xray profile", acceptedProfile?.name)
        }

    @Test
    fun multiOutboundJsonImportsSupportedAndSkipsVmess() =
        runTest {
            val persistence = RecordingPersistence()
            val vm = viewModel(persistence)
            // A raw-JSON config has no typed Xray profile (the validated parser
            // only derives one from a share link), so the libXray Xray option
            // cannot persist it. The multi-outbound skip surfacing is a
            // native-translation concern, so this exercises the native option.
            vm.selectOption(XrayServiceModeOption.NativeProxy)
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
            // Native option threads no typed Xray profile.
            assertNull(persistence.persisted?.third)
        }

    @Test
    fun rawJsonXrayOptionFailsClosedWithoutTypedProfile() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.XrayVpn)
        // A valid REALITY config pasted as raw JSON translates to a native relay,
        // but the validated parser derives a typed profile only from a share link,
        // so the libXray Xray option fails closed at validate-time (Finish stays off).
        val config =
            """
            { "outbounds": [ {
              "tag": "reality", "protocol": "vless",
              "settings": { "vnext": [ { "address": "edge.example.com", "port": 443,
                "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "reality",
                "realitySettings": { "publicKey": "$pbk", "serverName": "www.cloudflare.com", "shortId": "ab12" } }
            } ] }
            """.trimIndent()
        vm.onRawInputChange(config)
        vm.validate()

        val state = vm.uiState.value
        assertFalse(state.acceptedConfigReady)
        assertFalse(state.canFinish)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun multipleSupportedActivatesFirstAndDefersRest() {
        val vm = viewModel()
        vm.selectOption(XrayServiceModeOption.NativeProxy)
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
    fun persistFailureSurfacesErrorResetsGuardAndAllowsRetry() =
        runTest {
            val persistence = FlakyPersistence()
            val vm = XrayProfileImportViewModel(persistence)
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            vm.onRawInputChange(
                "vless://$uuid@edge.example.com:443?security=reality&pbk=$pbk&sni=h#n",
            )
            vm.validate()

            // First confirm: persist throws → error surfaced, not imported, no crash.
            vm.confirm()
            advanceUntilIdle()
            assertEquals("activation-failed", vm.uiState.value.errorMessage)

            // The in-flight guard was reset, so a retry can proceed and succeed.
            vm.importedEvents.test {
                vm.confirm()
                advanceUntilIdle()
                awaitItem()
            }
            assertEquals(2, persistence.attempts)
        }

    @Test
    fun nativeOptionFinishesWithoutProfile() =
        runTest {
            val persistence = RecordingPersistence()
            val vm = viewModel(persistence)
            vm.selectOption(XrayServiceModeOption.NativeDirect)
            assertTrue(vm.uiState.value.canFinish)
            vm.importedEvents.test {
                vm.confirm()
                advanceUntilIdle()
                awaitItem()
            }
            assertEquals(XrayServiceModeOption.NativeDirect, persistence.persisted?.first)
            assertTrue(persistence.persisted?.second?.isEmpty() == true)
            // Native options never thread a typed Xray profile.
            assertNull(persistence.persisted?.third)
        }

    @Test
    fun nonRealityFirstOutboundFailsClosedAndBlocksFinish() =
        runTest {
            val persistence = RecordingPersistence()
            val vm = viewModel(persistence)
            vm.selectOption(XrayServiceModeOption.XrayVpn)
            // Trojan translates to a native relay but is NOT a libXray-runnable
            // VLESS/REALITY profile, so the validated parser rejects it: the Xray
            // option fails closed at validate-time instead of enabling Finish and
            // throwing the generic persist error at confirm.
            vm.onRawInputChange("trojan://pw@tj.example:443#only")
            vm.validate()
            val state = vm.uiState.value
            assertFalse(state.acceptedConfigReady)
            assertFalse(state.canFinish)
            assertNotNull(state.errorMessage)

            // Confirm is a no-op while Finish is disabled: nothing is persisted.
            vm.confirm()
            advanceUntilIdle()
            assertNull(persistence.persisted)
        }
}
