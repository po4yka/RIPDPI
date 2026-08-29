package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.poyka.ripdpi.config.relay.resolveRelayPresetSuggestion
import com.poyka.ripdpi.config.relay.toRelayPresetReason
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.LatestDirectModeOutcomeSnapshot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayFinalmaskTypeFragment
import com.poyka.ripdpi.data.RelayFinalmaskTypeNoise
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModeCloudflareMtls
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.RelayPresetDefinition
import com.poyka.ripdpi.data.RelayPresetSuggestion
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.RelayXhttpModeStreamOne
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import com.poyka.ripdpi.services.MasquePrivacyPassAvailability
import com.poyka.ripdpi.services.MasquePrivacyPassBuildStatus
import com.poyka.ripdpi.ui.screens.xray.XrayNativeProviderSelection
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.poyka.ripdpi.data.FailureClass as RuntimeFailureClass

private const val sampleMasqueValue = "sample-masque-value"
private const val validRealityPublicKey = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s="
private const val validVlessUuid = "00000000-0000-0000-0000-000000000000"

private val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()

private fun sampleCertificatePem(): String =
    listOf(
        "-----BEGIN CERTIFICATE-----",
        "fixture",
        "-----END CERTIFICATE-----",
    ).joinToString("\n")

private fun samplePrivateKeyPem(): String =
    listOf(
        "-----BEGIN PRIVATE" + " KEY-----",
        "fixture",
        "-----END PRIVATE" + " KEY-----",
    ).joinToString("\n")

private fun validNaiveProxyDraft(relayNaivePath: String): ConfigDraft =
    defaultDraft.copy(
        relayEnabled = true,
        relayKind = RelayKindNaiveProxy,
        relayServer = "relay.example",
        relayServerPort = "443",
        relayServerName = "relay.example",
        relayNaiveUsername = "naive-user",
        relayNaivePassword = "naive-" + "credential",
        relayNaivePath = relayNaivePath,
    )

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConfigViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `config draft defaults match canonical encrypted dns settings`() {
        val defaultDns = canonicalDefaultEncryptedDnsSettings()
        val draft = ConfigDraft()

        assertEquals(defaultDns.dnsIp, draft.dnsIp)
        assertEquals(defaultDns.summary(), draft.dnsSummary)
    }

    @Test
    fun `manual vless xhttp mode round trips through settings and profile record`() {
        val draft =
            defaultDraft.copy(
                relayEnabled = true,
                relayKind = RelayKindVlessReality,
                relayServer = "relay.example",
                relayServerPort = "443",
                relayServerName = "relay.example",
                relayRealityPublicKey = validRealityPublicKey,
                relayRealityShortId = "",
                relayVlessTransport = RelayVlessTransportXhttp,
                relayXhttpPath = "/xhttp",
                relayXhttpHost = "cdn.example",
                relayXhttpMode = RelayXhttpModeStreamOne,
                relayVlessUuid = validVlessUuid,
            )

        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .applyConfigDraft(draft)
                .build()
        val restoredDraft = settings.toConfigDraft()
        val profile = draft.toRelayProfileRecord("manual")

        assertEquals(RelayXhttpModeStreamOne, settings.relayXhttpMode)
        assertEquals(RelayXhttpModeStreamOne, restoredDraft.relayXhttpMode)
        assertEquals(RelayXhttpModeStreamOne, profile.xhttpMode)
    }

    @Test
    fun `default draft marks recommended preset as selected`() {
        val presets = buildConfigPresets(defaultDraft)

        assertTrue(presets.first { it.id == "recommended" }.isSelected)
        assertFalse(presets.first { it.id == "proxy" }.isSelected)
        assertFalse(presets.first { it.id == "custom" }.isSelected)
    }

    @Test
    fun `proxy draft marks proxy preset as selected`() {
        val presets = buildConfigPresets(defaultDraft.copy(mode = Mode.Proxy))

        assertFalse(presets.first { it.id == "recommended" }.isSelected)
        assertTrue(presets.first { it.id == "proxy" }.isSelected)
        assertFalse(presets.first { it.id == "custom" }.isSelected)
    }

    @Test
    fun `invalid draft reports validation errors`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    proxyPort = "0",
                    maxConnections = "0",
                    bufferSize = "0",
                    defaultTtl = "999",
                ),
            )

        assertEquals("invalid_port", errors[ConfigFieldProxyPort])
        assertEquals("out_of_range", errors[ConfigFieldMaxConnections])
        assertEquals("out_of_range", errors[ConfigFieldBufferSize])
        assertEquals("out_of_range", errors[ConfigFieldDefaultTtl])
    }

    @Test
    fun `config draft surfaces custom dot dns summary`() {
        val draft =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsIp("9.9.9.9")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDot)
                .setEncryptedDnsHost("dot.example.test")
                .setEncryptedDnsPort(853)
                .setEncryptedDnsTlsServerName("dot.example.test")
                .clearEncryptedDnsBootstrapIps()
                .addAllEncryptedDnsBootstrapIps(listOf("9.9.9.9"))
                .build()
                .toConfigDraft()

        assertEquals("9.9.9.9", draft.dnsIp)
        assertEquals("Encrypted DNS · Custom resolver (DoT)", draft.dnsSummary)
    }

    @Test
    fun `config draft surfaces plain dns and dnscrypt summaries`() {
        val plainDraft =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsIp("9.9.9.9")
                .setDnsMode(DnsModePlainUdp)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol("")
                .setEncryptedDnsHost("")
                .setEncryptedDnsPort(0)
                .setEncryptedDnsTlsServerName("")
                .clearEncryptedDnsBootstrapIps()
                .setEncryptedDnsDohUrl("")
                .setEncryptedDnsDnscryptProviderName("")
                .setEncryptedDnsDnscryptPublicKey("")
                .build()
                .toConfigDraft()

        val dnsCryptDraft =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsIp("8.8.8.8")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDnsCrypt)
                .setEncryptedDnsHost("dnscrypt.example.test")
                .setEncryptedDnsPort(5443)
                .clearEncryptedDnsBootstrapIps()
                .addAllEncryptedDnsBootstrapIps(listOf("8.8.8.8"))
                .setEncryptedDnsDnscryptProviderName("2.dnscrypt-cert.example.test")
                .setEncryptedDnsDnscryptPublicKey(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ).build()
                .toConfigDraft()

        assertEquals("Plain DNS · 9.9.9.9", plainDraft.dnsSummary)
        assertEquals("Encrypted DNS · Custom resolver (DNSCrypt)", dnsCryptDraft.dnsSummary)
    }

    @Test
    fun `relay validation accepts hysteria salamander`() {
        val hysteriaErrors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindHysteria2,
                    relayServer = "relay.example",
                    relayServerName = "relay.example",
                    relayHysteriaPassword = "fixture-pass",
                    relayHysteriaSalamanderKey = "salamander",
                    relayUdpEnabled = true,
                ),
            )

        assertEquals(null, hysteriaErrors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects VLESS xHTTP with UDP enabled`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindVlessReality,
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayServerName = "relay.example",
                    relayRealityPublicKey = validRealityPublicKey,
                    relayRealityShortId = "abcd1234",
                    relayVlessTransport = RelayVlessTransportXhttp,
                    relayXhttpPath = "/xhttp",
                    relayVlessUuid = validVlessUuid,
                    relayUdpEnabled = true,
                ),
            )

        assertEquals("unsupported", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation accepts VLESS Reality without short id`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindVlessReality,
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayServerName = "relay.example",
                    relayRealityPublicKey = validRealityPublicKey,
                    relayRealityShortId = "",
                    relayVlessUuid = validVlessUuid,
                ),
            )

        assertEquals(null, errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects malformed VLESS Reality public key`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindVlessReality,
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayServerName = "relay.example",
                    relayRealityPublicKey = "not-a-valid-reality-public-key",
                    relayRealityShortId = "",
                    relayVlessUuid = validVlessUuid,
                ),
            )

        assertEquals("invalid", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects malformed VLESS Reality short id`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindVlessReality,
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayServerName = "relay.example",
                    relayRealityPublicKey = validRealityPublicKey,
                    relayRealityShortId = "not-hex",
                    relayVlessUuid = validVlessUuid,
                ),
            )

        assertEquals("invalid", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects malformed VLESS Reality uuid`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindVlessReality,
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayServerName = "relay.example",
                    relayRealityPublicKey = validRealityPublicKey,
                    relayRealityShortId = "",
                    relayVlessUuid = "not-a-uuid",
                ),
            )

        assertEquals("invalid", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects Cloudflare Tunnel UDP mode and missing uuid`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindCloudflareTunnel,
                    relayServer = "edge.example.com",
                    relayVlessTransport = RelayVlessTransportXhttp,
                    relayUdpEnabled = true,
                ),
            )

        assertEquals("unsupported", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation requires Cloudflare publish origin and credentials`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindCloudflareTunnel,
                    relayCloudflareTunnelMode = RelayCloudflareTunnelModePublishLocalOrigin,
                    relayServer = "edge.example.com",
                    relayServerName = "edge.example.com",
                    relayVlessTransport = RelayVlessTransportXhttp,
                ),
            )

        assertEquals("required", errors[ConfigFieldRelayCloudflarePublishOrigin])
        assertEquals("required", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation accepts supported finalmask fragment settings`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindCloudflareTunnel,
                    relayServer = "edge.example.com",
                    relayServerPort = "443",
                    relayServerName = "edge.example.com",
                    relayFinalmaskType = RelayFinalmaskTypeFragment,
                    relayFinalmaskFragmentPackets = "3",
                    relayFinalmaskFragmentMinBytes = "32",
                    relayFinalmaskFragmentMaxBytes = "96",
                ),
            )

        assertEquals(null, errors[ConfigFieldRelayFinalmask])
    }

    @Test
    fun `relay validation rejects finalmask for unsupported relay kinds`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindMasque,
                    relayMasqueUrl = "https://masque.example/",
                    relayMasqueAuthMode = RelayMasqueAuthModeBearer,
                    relayMasqueAuthToken = sampleMasqueValue,
                    relayFinalmaskType = RelayFinalmaskTypeFragment,
                    relayFinalmaskFragmentPackets = "3",
                    relayFinalmaskFragmentMinBytes = "32",
                    relayFinalmaskFragmentMaxBytes = "96",
                ),
            )

        assertEquals("unsupported", errors[ConfigFieldRelayFinalmask])
    }

    @Test
    fun `relay validation rejects finalmask for reality tcp transport`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindVlessReality,
                    relayServer = "edge.example.com",
                    relayServerPort = "443",
                    relayServerName = "edge.example.com",
                    relayRealityPublicKey = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
                    relayVlessUuid = "00000000-0000-0000-0000-000000000000",
                    relayFinalmaskType = RelayFinalmaskTypeFragment,
                    relayFinalmaskFragmentPackets = "3",
                    relayFinalmaskFragmentMinBytes = "32",
                    relayFinalmaskFragmentMaxBytes = "96",
                ),
            )

        assertEquals("unsupported", errors[ConfigFieldRelayFinalmask])
    }

    @Test
    fun `relay validation accepts finalmask noise mode for xhttp transports`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindCloudflareTunnel,
                    relayServer = "edge.example.com",
                    relayServerPort = "443",
                    relayServerName = "edge.example.com",
                    relayFinalmaskType = RelayFinalmaskTypeNoise,
                    relayFinalmaskRandRange = "8-12",
                ),
            )

        assertEquals(null, errors[ConfigFieldRelayFinalmask])
    }

    @Test
    fun `relay validation accepts tuic udp mode when required fields are present`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindTuicV5,
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayServerName = "relay.example",
                    relayTuicUuid = "00000000-0000-0000-0000-000000000000",
                    relayTuicPassword = "fixture-pass",
                    relayUdpEnabled = true,
                ),
            )

        assertEquals(null, errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects naiveproxy missing credentials`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindNaiveProxy,
                    relayServer = "relay.example",
                    relayServerPort = "443",
                    relayServerName = "relay.example",
                ),
            )

        assertEquals("required", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation accepts naiveproxy blank or absolute path`() {
        val blankPathErrors =
            validateConfigDraft(
                validNaiveProxyDraft(relayNaivePath = ""),
            )
        val absolutePathErrors =
            validateConfigDraft(
                validNaiveProxyDraft(relayNaivePath = "/proxy"),
            )

        assertEquals(null, blankPathErrors[ConfigFieldRelayNaivePath])
        assertEquals(null, absolutePathErrors[ConfigFieldRelayNaivePath])
        assertEquals(null, blankPathErrors[ConfigFieldRelayCredentials])
        assertEquals(null, absolutePathErrors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects naiveproxy relative path`() {
        val errors =
            validateConfigDraft(
                validNaiveProxyDraft(relayNaivePath = "proxy"),
            )

        assertEquals("absolute_path", errors[ConfigFieldRelayNaivePath])
    }

    @Test
    fun `relay validation rejects shadowtls udp mode`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindShadowTlsV3,
                    relayShadowTlsInnerProfileId = "inner-profile",
                    relayShadowTlsPassword = "fixture-pass",
                    relayUdpEnabled = true,
                ),
            )

        assertEquals("unsupported", errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation rejects masque privacy pass when provider is unavailable`() {
        val masqueErrors =
            validateConfigDraft(
                defaultDraft.copy(
                    relayEnabled = true,
                    relayKind = RelayKindMasque,
                    relayMasqueUrl = "https://masque.example/",
                    relayMasqueAuthMode = RelayMasqueAuthModePrivacyPass,
                    relayUdpEnabled = true,
                ),
            )

        assertEquals("unsupported", masqueErrors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation accepts masque privacy pass when provider is available`() {
        val masqueErrors =
            validateConfigDraft(
                draft =
                    defaultDraft.copy(
                        relayEnabled = true,
                        relayKind = RelayKindMasque,
                        relayMasqueUrl = "https://masque.example/",
                        relayMasqueAuthMode = RelayMasqueAuthModePrivacyPass,
                        relayUdpEnabled = true,
                    ),
                supportsMasquePrivacyPass = true,
            )

        assertEquals(null, masqueErrors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `relay validation accepts masque cloudflare direct when certificate material is present`() {
        val errors =
            validateConfigDraft(
                draft =
                    defaultDraft.copy(
                        relayEnabled = true,
                        relayKind = RelayKindMasque,
                        relayMasqueUrl = "https://masque.example/",
                        relayMasqueAuthMode = RelayMasqueAuthModeCloudflareMtls,
                        relayMasqueClientCertificateChainPem =
                            sampleCertificatePem(),
                        relayMasqueClientPrivateKeyPem =
                            samplePrivateKeyPem(),
                    ),
                supportsMasquePrivacyPass = true,
            )

        assertEquals(null, errors[ConfigFieldRelayCredentials])
    }

    @Test
    fun `sanitize downgrades privacy pass draft when provider is unavailable`() {
        val sanitized =
            sanitizeMasqueAuthModeForCurrentBuild(
                draft =
                    defaultDraft.copy(
                        relayKind = RelayKindMasque,
                        relayMasqueAuthMode = RelayMasqueAuthModePrivacyPass,
                    ),
                supportsMasquePrivacyPass = false,
            )

        assertEquals(RelayMasqueAuthModeBearer, sanitized.relayMasqueAuthMode)
    }

    @Test
    fun `relay preset suggestion requires runtime evidence`() {
        val suggestion =
            resolveRelayPresetSuggestion(
                heuristicSuggestion =
                    RelayPresetSuggestion(
                        preset = RelayPresetDefinition(id = "ru-mobile-relay", title = "Russian mobile relay"),
                        reason = "heuristic only",
                    ),
                serviceTelemetry = ServiceTelemetrySnapshot(),
                capabilityRecords = emptyList(),
            )

        assertEquals(null, suggestion)
    }

    @Test
    fun `relay preset suggestion uses whitelist pressure evidence`() {
        val suggestion =
            resolveRelayPresetSuggestion(
                heuristicSuggestion =
                    RelayPresetSuggestion(
                        preset = RelayPresetDefinition(id = "ru-mobile-relay", title = "Russian mobile relay"),
                        reason = "heuristic only",
                    ),
                serviceTelemetry =
                    ServiceTelemetrySnapshot(
                        status = AppStatus.Running,
                        runtimeFieldTelemetry =
                            RuntimeFieldTelemetry(
                                failureClass = RuntimeFailureClass.FingerprintPolicy,
                            ),
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                lastError = "whitelist_sni_failed",
                            ),
                    ),
                capabilityRecords = emptyList(),
            )

        assertTrue(suggestion?.reason?.contains("whitelist-style routing pressure") == true)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConfigViewModelProviderModeTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `selecting vpn mode preserves existing xray provider selection`() =
        runTest {
            val repository =
                FakeAppSettingsRepository(
                    initialSettings =
                        com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setRipdpiMode(Mode.Proxy.preferenceValue)
                            .build(),
                )
            val xrayNativeProviderSelection = RecordingXrayNativeProviderSelection(repository)
            val viewModel =
                createConfigViewModel(
                    appSettingsRepository = repository,
                    xrayNativeProviderSelection = xrayNativeProviderSelection,
                )

            viewModel.selectMode(Mode.VPN)
            advanceUntilIdle()

            assertTrue(xrayNativeProviderSelection.selectedModes.isEmpty())
            assertEquals(Mode.VPN.preferenceValue, repository.snapshot().ripdpiMode)
        }

    @Test
    fun `saving vpn draft preserves existing xray provider selection`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val xrayNativeProviderSelection = RecordingXrayNativeProviderSelection(repository)
            val viewModel =
                createConfigViewModel(
                    appSettingsRepository = repository,
                    xrayNativeProviderSelection = xrayNativeProviderSelection,
                )

            viewModel.updateDraft { copy(mode = Mode.VPN, proxyPort = "1081") }
            viewModel.saveDraft()
            advanceUntilIdle()

            assertTrue(xrayNativeProviderSelection.selectedModes.isEmpty())
        }

    @Test
    fun `saveDraft leaves halted runtime for next start`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.VPN)
            val viewModel =
                createConfigViewModel(
                    serviceController = serviceController,
                    serviceStateStore = serviceStateStore,
                )

            viewModel.updateDraft { copy(proxyPort = "1081") }
            viewModel.saveDraft()
            advanceUntilIdle()

            assertEquals(0, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
        }

    @Test
    fun `saveDraft restarts running runtime with saved mode`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val viewModel =
                createConfigViewModel(
                    serviceController = serviceController,
                    serviceStateStore = serviceStateStore,
                )

            viewModel.updateDraft { copy(mode = Mode.Proxy, proxyPort = "1081") }
            viewModel.saveDraft()
            runCurrent()

            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())

            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
        }

    @Test
    fun `saveDraft applies edited vpn mode after active proxy service halts`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val viewModel =
                createConfigViewModel(
                    serviceController = serviceController,
                    serviceStateStore = serviceStateStore,
                )

            viewModel.updateDraft { copy(mode = Mode.VPN, proxyPort = "1081") }
            viewModel.saveDraft()
            runCurrent()

            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())

            serviceStateStore.setStatus(AppStatus.Halted, Mode.Proxy)
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
        }

    private fun createConfigViewModel(
        appSettingsRepository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
        serviceController: FakeServiceController = FakeServiceController(),
        xrayNativeProviderSelection: XrayNativeProviderSelection =
            RecordingXrayNativeProviderSelection(appSettingsRepository),
    ): ConfigViewModel {
        val relayProfileStore = InMemoryRelayProfileStore()
        val relayCredentialStore = InMemoryRelayCredentialStore()
        return ConfigViewModel(
            savedStateHandle = SavedStateHandle(),
            dependencies =
                ConfigViewModelDependencies(
                    appSettingsRepository = appSettingsRepository,
                    relayArtifacts =
                        ConfigRelayArtifactRepository(
                            appSettingsRepository = appSettingsRepository,
                            relayProfileStore = relayProfileStore,
                            relayCredentialStore = relayCredentialStore,
                        ),
                    relayPresetCatalog = RelayPresetCatalog(RuntimeEnvironment.getApplication()),
                    networkSnapshotProvider = FakeNativeNetworkSnapshotProvider(),
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                    latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
                    capabilityObserver =
                        ConfigCapabilityObserver(
                            networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                            serverCapabilityStore = NoOpServerCapabilityStore(),
                            dispatchers = testDispatchers(),
                        ),
                    dispatchers = testDispatchers(),
                    editorDraftStore = InMemoryConfigEditorDraftStore(),
                    xrayNativeProviderSelection = xrayNativeProviderSelection,
                ),
            importDependencies =
                ConfigImportDependencies(
                    masqueClientCredentialImporter = NoOpMasqueClientCredentialImporter,
                    masquePrivacyPassAvailability = NoOpMasquePrivacyPassAvailability,
                ),
            stringResolver = ResourceStringResolver(),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConfigViewModelPersistenceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `dirty mode editor draft and credentials survive process recreation via opaque saved state`() =
        runTest {
            val store = InMemoryConfigEditorDraftStore()
            val firstHandle = SavedStateHandle()
            val first = createConfigViewModel(savedStateHandle = firstHandle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { first.uiState.collect() }
            advanceUntilIdle()

            first.startEditingPreset()
            advanceUntilIdle()
            first.updateDraft {
                copy(
                    proxyPort = "1087",
                    relayMasqueAuthToken = "fixture-recovery-marker",
                    relayMasqueClientPrivateKeyPem = samplePrivateKeyPem(),
                )
            }
            advanceUntilIdle()

            val recoverySessionId =
                requireNotNull(firstHandle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            assertEquals(setOf(ConfigEditorRecoverySessionIdSavedStateKey), firstHandle.keys())
            assertFalse(recoverySessionId.contains("fixture-recovery-marker"))

            val restored =
                createConfigViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(ConfigEditorRecoverySessionIdSavedStateKey to recoverySessionId),
                        ),
                    editorDraftStore = store,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.uiState.collect() }
            advanceUntilIdle()

            assertEquals("1087", restored.uiState.value.draft.proxyPort)
            assertEquals("fixture-recovery-marker", restored.uiState.value.draft.relayMasqueAuthToken)
            assertEquals(samplePrivateKeyPem(), restored.uiState.value.draft.relayMasqueClientPrivateKeyPem)
            assertTrue(restored.uiState.value.isEditorDirty)
            assertFalse(restored.uiState.value.isEditorLoading)
        }

    @Test
    fun `discard rotates opaque recovery id and stale record cannot be restored`() =
        runTest {
            val store = InMemoryConfigEditorDraftStore()
            val handle = SavedStateHandle()
            val first = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { first.uiState.collect() }
            advanceUntilIdle()
            first.startEditingPreset()
            advanceUntilIdle()
            first.updateDraft { copy(proxyPort = "1087") }
            advanceUntilIdle()
            val staleId = requireNotNull(handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))

            assertTrue(first.cancelEditing())
            val rotatedId = requireNotNull(handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            advanceUntilIdle()

            assertFalse(staleId == rotatedId)
            val recreated =
                createConfigViewModel(
                    savedStateHandle = SavedStateHandle(mapOf(ConfigEditorRecoverySessionIdSavedStateKey to staleId)),
                    editorDraftStore = store,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.uiState.collect() }
            advanceUntilIdle()
            assertFalse(recreated.uiState.value.isEditorDirty)
            assertEquals(defaultDraft, recreated.uiState.value.draft)
        }

    @Test
    fun `saved state invalidation tombstone blocks a discarded draft after process death`() =
        runTest {
            val staleId = newConfigEditorRecoverySessionId()
            val store = InMemoryConfigEditorDraftStore()
            store.persist(
                staleId,
                ConfigEditorSession(
                    presetId = "custom",
                    baselineDraft = defaultDraft,
                    draft = defaultDraft.copy(relayMasqueAuthToken = "fixture-discarded-marker"),
                    draftRevision = 1L,
                ),
            )
            val handle =
                SavedStateHandle(
                    mapOf(
                        ConfigEditorRecoverySessionIdSavedStateKey to staleId,
                        ConfigEditorInvalidatedRecoverySessionIdsSavedStateKey to arrayListOf(staleId),
                    ),
                )

            val recreated = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.uiState.collect() }
            advanceUntilIdle()

            assertFalse(recreated.uiState.value.isEditorDirty)
            assertEquals(defaultDraft, recreated.uiState.value.draft)
            assertFalse(staleId == handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
        }

    @Test
    fun `old saved state tombstone preserves the current draft after process death`() =
        runTest {
            val oldId = newConfigEditorRecoverySessionId()
            val currentId = newConfigEditorRecoverySessionId()
            val currentDraft = defaultDraft.copy(relayMasqueAuthToken = "fixture-current-recovery")
            val store = InMemoryConfigEditorDraftStore()
            store.persist(
                currentId,
                ConfigEditorSession(
                    presetId = "custom",
                    baselineDraft = defaultDraft,
                    draft = currentDraft,
                    draftRevision = 1L,
                ),
            )
            val handle =
                SavedStateHandle(
                    mapOf(
                        ConfigEditorRecoverySessionIdSavedStateKey to currentId,
                        ConfigEditorInvalidatedRecoverySessionIdsSavedStateKey to arrayListOf(oldId),
                    ),
                )

            val recreated = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.uiState.collect() }
            advanceUntilIdle()

            assertTrue(recreated.uiState.value.isEditorDirty)
            assertEquals(currentDraft, recreated.uiState.value.draft)
        }

    @Test
    fun `exit stays blocked until late recovery resolves`() =
        runTest {
            val staleId = newConfigEditorRecoverySessionId()
            val staleDraft = defaultDraft.copy(proxyPort = "1087", relayMasqueAuthToken = "fixture-stale-marker")
            val store = GatedRestoreConfigEditorDraftStore()
            val handle = SavedStateHandle(mapOf(ConfigEditorRecoverySessionIdSavedStateKey to staleId))
            val viewModel = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            runCurrent()
            store.restoreStarted.await()

            assertFalse(viewModel.cancelEditing())
            assertEquals(ConfigEditorExitDecision.Blocked, viewModel.requestEditorExit())
            store.restoreResult.complete(
                ConfigEditorDraftRestoreResult.Restored(
                    ConfigEditorSession(
                        presetId = "custom",
                        baselineDraft = defaultDraft,
                        draft = staleDraft,
                        draftRevision = 1L,
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(staleId, handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            assertTrue(viewModel.uiState.value.isEditorDirty)
            assertFalse(viewModel.uiState.value.isEditorLoading)
            assertEquals(staleDraft, viewModel.uiState.value.draft)
            assertFalse(store.deletedSessionIds.contains(staleId))
            assertEquals(ConfigEditorExitDecision.ConfirmDiscard, viewModel.requestEditorExit())
        }

    @Test
    fun `failed durable invalidation keeps the current draft recoverable`() =
        runTest {
            val failure = IllegalStateException("fixture invalidation failure")
            val store = InMemoryConfigEditorDraftStore(invalidationFailure = failure)
            val handle = SavedStateHandle()
            val viewModel = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            val errorEffect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }
            advanceUntilIdle()
            viewModel.startEditingPreset()
            viewModel.updateDraft { copy(proxyPort = "1087") }
            advanceUntilIdle()
            val recoverySessionId = requireNotNull(handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))

            assertFalse(viewModel.cancelEditing())

            assertTrue(viewModel.uiState.value.isEditorDirty)
            assertEquals("1087", viewModel.uiState.value.draft.proxyPort)
            assertEquals(recoverySessionId, handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            assertFalse(handle.contains(ConfigEditorInvalidatedRecoverySessionIdsSavedStateKey))
            assertEquals(ConfigEffect.Message("unexpected error"), errorEffect.await())
        }

    @Test
    fun `save invalidation failure keeps the draft and does not report success`() =
        runTest {
            val store = InMemoryConfigEditorDraftStore(invalidationFailure = java.io.IOException("fixture failure"))
            val handle = SavedStateHandle()
            val viewModel = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()
            viewModel.startEditingPreset()
            advanceUntilIdle()
            viewModel.updateDraft { copy(proxyPort = "1087") }
            advanceUntilIdle()
            val recoverySessionId = requireNotNull(handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            val effects = mutableListOf<ConfigEffect>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.effects.collect { effects += it }
                }

            viewModel.saveDraft()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isEditorDirty)
            assertFalse(viewModel.uiState.value.isEditorSaving)
            assertEquals("1087", viewModel.uiState.value.draft.proxyPort)
            assertEquals(recoverySessionId, handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            assertEquals(listOf(ConfigEffect.Message("unexpected error")), effects)

            val recreated = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.uiState.collect() }
            advanceUntilIdle()

            assertTrue(recreated.uiState.value.isEditorDirty)
            assertEquals("1087", recreated.uiState.value.draft.proxyPort)
            collector.cancel()
        }

    @Test
    fun `mode draft persistence warning remains visible until automatic retry succeeds`() =
        runTest {
            val store = InMemoryConfigEditorDraftStore(persistFailuresRemaining = 1)
            val handle = SavedStateHandle()
            val viewModel = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()
            viewModel.startEditingPreset()
            advanceUntilIdle()

            viewModel.updateDraft { copy(proxyPort = "1087") }
            runCurrent()

            assertEquals(1, store.persistCalls)
            assertTrue(viewModel.uiState.value.hasEditorRecoveryPersistenceError)

            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(2, store.persistCalls)
            assertFalse(viewModel.uiState.value.hasEditorRecoveryPersistenceError)
            val recoverySessionId = requireNotNull(handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            val recreated =
                createConfigViewModel(
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(ConfigEditorRecoverySessionIdSavedStateKey to recoverySessionId),
                        ),
                    editorDraftStore = store,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.uiState.collect() }
            advanceUntilIdle()
            assertEquals("1087", recreated.uiState.value.draft.proxyPort)
        }

    @Test
    fun `durably invalidated saved state id rotates before accepting a new draft`() =
        runTest {
            val staleId = newConfigEditorRecoverySessionId()
            val store = InMemoryConfigEditorDraftStore()
            store.invalidate(staleId)
            val handle = SavedStateHandle(mapOf(ConfigEditorRecoverySessionIdSavedStateKey to staleId))
            val viewModel = createConfigViewModel(savedStateHandle = handle, editorDraftStore = store)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()

            val currentId = requireNotNull(handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            assertFalse(staleId == currentId)
            viewModel.startEditingPreset()
            advanceUntilIdle()
            viewModel.updateDraft { copy(proxyPort = "1087") }
            advanceUntilIdle()

            val recreated =
                createConfigViewModel(
                    savedStateHandle = SavedStateHandle(mapOf(ConfigEditorRecoverySessionIdSavedStateKey to currentId)),
                    editorDraftStore = store,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.uiState.collect() }
            advanceUntilIdle()

            assertTrue(recreated.uiState.value.isEditorDirty)
            assertEquals("1087", recreated.uiState.value.draft.proxyPort)
        }

    @Test
    fun `suppressed successful save rotates recovery and cannot restore saved draft`() =
        runTest {
            val store = InMemoryConfigEditorDraftStore()
            val handle = SavedStateHandle()
            val saveGate = CompletableDeferred<Unit>()
            val viewModel =
                createConfigViewModel(
                    savedStateHandle = handle,
                    relayProfileStore = ControllableRelayProfileStore(saveGate = saveGate),
                    editorDraftStore = store,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()
            viewModel.startEditingPreset()
            advanceUntilIdle()
            viewModel.updateDraft { copy(proxyPort = "1087") }
            advanceUntilIdle()
            val staleId = requireNotNull(handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))

            viewModel.saveDraft()
            runCurrent()
            viewModel.selectMode(Mode.Proxy)
            saveGate.complete(Unit)
            advanceUntilIdle()

            val rotatedId = requireNotNull(handle.get<String>(ConfigEditorRecoverySessionIdSavedStateKey))
            assertFalse(staleId == rotatedId)
            assertFalse(viewModel.uiState.value.isEditorDirty)
            val recreated =
                createConfigViewModel(
                    savedStateHandle = SavedStateHandle(mapOf(ConfigEditorRecoverySessionIdSavedStateKey to staleId)),
                    editorDraftStore = store,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { recreated.uiState.collect() }
            advanceUntilIdle()
            assertFalse(recreated.uiState.value.isEditorDirty)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConfigViewModelEditorSessionConcurrencyTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `throwing hydration aborts the exact editor session and allows retry`() =
        runTest {
            val profileStore = ControllableRelayProfileStore(loadFailure = IllegalStateException("hydrate failed"))
            val viewModel = createConfigViewModel(relayProfileStore = profileStore)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            val errorEffect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }

            viewModel.startEditingPreset()
            advanceUntilIdle()

            val effect = errorEffect.await()
            val failure = effect as ConfigEffect.EditorHydrationFailed
            assertTrue(viewModel.uiState.value.isEditorLoading)
            assertFalse(viewModel.uiState.value.isEditorDirty)
            assertFalse(viewModel.editorHydrationFailures.abort(failure.sessionId + 1))
            assertTrue(viewModel.editorHydrationFailures.abort(failure.sessionId))
            assertFalse(viewModel.uiState.value.isEditorLoading)
            assertEquals(defaultDraft, viewModel.uiState.value.draft)

            viewModel.startEditingPreset()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isEditorLoading)
            assertEquals(defaultDraft, viewModel.uiState.value.draft)
        }

    @Test
    fun `failed hydration cannot overwrite existing relay credentials`() =
        runTest {
            val profileStore = ControllableRelayProfileStore(loadFailure = IllegalStateException("hydrate failed"))
            val credentialStore = InMemoryRelayCredentialStore()
            val preservedCredential = listOf("preserved", "fixture").joinToString("-")
            val existingCredentials =
                RelayCredentialRecord(
                    profileId = DefaultRelayProfileId,
                    vlessUuid = "preserved-vless-uuid",
                    masqueAuthToken = preservedCredential,
                )
            credentialStore.save(existingCredentials)
            val viewModel =
                createConfigViewModel(
                    relayProfileStore = profileStore,
                    relayCredentialStore = credentialStore,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            val errorEffect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }

            viewModel.startEditingPreset()
            advanceUntilIdle()
            assertTrue(errorEffect.await() is ConfigEffect.EditorHydrationFailed)

            viewModel.updateDraft { copy(proxyPort = "1081") }
            viewModel.saveDraft()
            advanceUntilIdle()

            assertEquals(existingCredentials, credentialStore.load(DefaultRelayProfileId))
            assertEquals(0, profileStore.saveCalls)
        }

    @Test
    fun `cancelled hydration restores the original draft and allows retry`() =
        runTest {
            val profileStore = ControllableRelayProfileStore(loadFailure = CancellationException("cancelled"))
            val viewModel = createConfigViewModel(relayProfileStore = profileStore)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.startEditingPreset()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isEditorLoading)
            assertFalse(viewModel.uiState.value.isEditorDirty)
            assertEquals(defaultDraft, viewModel.uiState.value.draft)

            viewModel.startEditingPreset()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isEditorLoading)
        }

    @Test
    fun `delayed built in preset update does not clear a newer editor session`() =
        runTest {
            val updateGate = CompletableDeferred<Unit>()
            val appSettingsRepository = GatedUpdateAppSettingsRepository(updateGate)
            val viewModel = createConfigViewModel(appSettingsRepository = appSettingsRepository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.startEditingPreset()
            advanceUntilIdle()
            viewModel.updateDraft { copy(proxyPort = "1081") }
            viewModel.selectPreset("recommended")
            runCurrent()

            viewModel.startEditingPreset()
            runCurrent()
            viewModel.updateDraft { copy(proxyPort = "1082") }

            updateGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                "1082",
                viewModel.uiState.value.editingPreset
                    ?.draft
                    ?.proxyPort,
            )
            assertTrue(viewModel.uiState.value.isEditorDirty)
        }

    @Test
    fun `same frame exit request observes the latest dirty draft`() =
        runTest {
            val viewModel = createConfigViewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.startEditingPreset()
            advanceUntilIdle()
            viewModel.updateDraft { copy(proxyPort = "1081") }

            val decision = viewModel.requestEditorExit()

            assertEquals(ConfigEditorExitDecision.ConfirmDiscard, decision)
            runCurrent()
            assertEquals("1081", viewModel.uiState.value.draft.proxyPort)
        }

    @Test
    fun `session scoped callback cannot create or mutate a stale editor session`() =
        runTest {
            val viewModel = createConfigViewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            assertFalse(
                viewModel.updateDraft(expectedSessionId = 41L) {
                    copy(relayMasqueCloudflareGeohashEnabled = true)
                },
            )
            assertFalse(viewModel.uiState.value.isEditorDirty)

            viewModel.startEditingPreset()
            advanceUntilIdle()
            val readySessionId = requireNotNull(viewModel.currentEditorSessionId)

            assertFalse(
                viewModel.updateDraft(expectedSessionId = readySessionId + 1L) {
                    copy(relayMasqueCloudflareGeohashEnabled = true)
                },
            )
            assertTrue(
                viewModel.updateDraft(expectedSessionId = readySessionId) {
                    copy(relayMasqueCloudflareGeohashEnabled = true)
                },
            )
            assertTrue(viewModel.uiState.value.draft.relayMasqueCloudflareGeohashEnabled)
        }

    @Test
    fun `stale masque import completions do not mutate a newer editor session`() =
        runTest {
            val importGate = CompletableDeferred<Unit>()
            val importer = ControllableMasqueClientCredentialImporter(importGate)
            val viewModel = createConfigViewModel(masqueClientCredentialImporter = importer)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.startEditingPreset()
            advanceUntilIdle()
            val oldSessionId = requireNotNull(viewModel.currentEditorSessionId)
            val uri = Uri.parse("content://test/identity")
            viewModel.importRelayMasqueCertificateChain(uri, oldSessionId)
            viewModel.importRelayMasquePrivateKey(uri, oldSessionId)
            viewModel.importRelayMasquePkcs12(uri, "password", oldSessionId)
            runCurrent()

            assertTrue(viewModel.cancelEditing())
            viewModel.startEditingPreset()
            runCurrent()
            viewModel.updateDraft { copy(proxyPort = "1082") }

            importGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(3, importer.callCount)
            assertEquals("1082", viewModel.uiState.value.draft.proxyPort)
            assertEquals("", viewModel.uiState.value.draft.relayMasqueClientCertificateChainPem)
            assertEquals("", viewModel.uiState.value.draft.relayMasqueClientPrivateKeyPem)
        }

    @Test
    fun `newer certificate import wins when completions arrive in reverse order`() =
        runTest {
            val older = CompletableDeferred<String>()
            val newer = CompletableDeferred<String>()
            val importer = OrderedMasqueClientCredentialImporter(certificateResults = listOf(older, newer))
            val viewModel = createConfigViewModel(masqueClientCredentialImporter = importer)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            viewModel.startEditingPreset()
            advanceUntilIdle()
            val sessionId = requireNotNull(viewModel.currentEditorSessionId)
            val uri = Uri.parse("content://test/certificate")

            viewModel.importRelayMasqueCertificateChain(uri, sessionId)
            runCurrent()
            viewModel.importRelayMasqueCertificateChain(uri, sessionId)
            runCurrent()
            newer.complete("newer-certificate")
            runCurrent()
            older.complete("older-certificate")
            advanceUntilIdle()

            assertEquals("newer-certificate", viewModel.uiState.value.draft.relayMasqueClientCertificateChainPem)
        }

    @Test
    fun `save waits for the active masque import to finish`() =
        runTest {
            val importGate = CompletableDeferred<Unit>()
            val importer = ControllableMasqueClientCredentialImporter(importGate)
            val profileStore = ControllableRelayProfileStore()
            val viewModel =
                createConfigViewModel(
                    relayProfileStore = profileStore,
                    masqueClientCredentialImporter = importer,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            viewModel.startEditingPreset()
            advanceUntilIdle()
            val sessionId = requireNotNull(viewModel.currentEditorSessionId)

            viewModel.importRelayMasqueCertificateChain(Uri.parse("content://test/certificate"), sessionId)
            runCurrent()
            assertTrue(viewModel.uiState.value.isEditorImporting)

            viewModel.saveDraft()
            runCurrent()
            assertEquals(0, profileStore.saveCalls)
            assertFalse(viewModel.uiState.value.isEditorSaving)

            importGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isEditorImporting)
            assertEquals(sampleCertificatePem(), viewModel.uiState.value.draft.relayMasqueClientCertificateChainPem)

            viewModel.saveDraft()
            advanceUntilIdle()
            assertEquals(1, profileStore.saveCalls)
        }

    @Test
    fun `newer pkcs12 import supersedes older certificate completion`() =
        runTest {
            val certificate = CompletableDeferred<String>()
            val pkcs12 = CompletableDeferred<ImportedMasqueClientIdentity>()
            val importer =
                OrderedMasqueClientCredentialImporter(
                    certificateResults = listOf(certificate),
                    pkcs12Results = listOf(pkcs12),
                )
            val viewModel = createConfigViewModel(masqueClientCredentialImporter = importer)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            viewModel.startEditingPreset()
            advanceUntilIdle()
            val sessionId = requireNotNull(viewModel.currentEditorSessionId)
            val uri = Uri.parse("content://test/identity")

            viewModel.importRelayMasqueCertificateChain(uri, sessionId)
            runCurrent()
            viewModel.importRelayMasquePkcs12(uri, "password", sessionId)
            runCurrent()
            pkcs12.complete(ImportedMasqueClientIdentity("pkcs12-certificate", "pkcs12-private-key"))
            runCurrent()
            certificate.complete("older-certificate")
            advanceUntilIdle()

            assertEquals("pkcs12-certificate", viewModel.uiState.value.draft.relayMasqueClientCertificateChainPem)
            assertEquals("pkcs12-private-key", viewModel.uiState.value.draft.relayMasqueClientPrivateKeyPem)
        }

    @Test
    fun `manual certificate correction supersedes pending certificate import`() =
        runTest {
            val certificate = CompletableDeferred<String>()
            val importer = OrderedMasqueClientCredentialImporter(certificateResults = listOf(certificate))
            val viewModel = createConfigViewModel(masqueClientCredentialImporter = importer)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            viewModel.startEditingPreset()
            advanceUntilIdle()
            val sessionId = requireNotNull(viewModel.currentEditorSessionId)

            viewModel.importRelayMasqueCertificateChain(Uri.parse("content://test/certificate"), sessionId)
            runCurrent()
            viewModel.updateRelayMasqueCertificateChain("manual-certificate")
            certificate.complete("imported-certificate")
            advanceUntilIdle()

            assertEquals("manual-certificate", viewModel.uiState.value.draft.relayMasqueClientCertificateChainPem)
        }

    @Test
    fun `manual private key correction supersedes pending pkcs12 import`() =
        runTest {
            val pkcs12 = CompletableDeferred<ImportedMasqueClientIdentity>()
            val importer = OrderedMasqueClientCredentialImporter(pkcs12Results = listOf(pkcs12))
            val viewModel = createConfigViewModel(masqueClientCredentialImporter = importer)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            viewModel.startEditingPreset()
            advanceUntilIdle()
            val sessionId = requireNotNull(viewModel.currentEditorSessionId)

            viewModel.importRelayMasquePkcs12(Uri.parse("content://test/identity"), "password", sessionId)
            runCurrent()
            viewModel.updateRelayMasquePrivateKey("manual-private-key")
            pkcs12.complete(ImportedMasqueClientIdentity("imported-certificate", "imported-private-key"))
            advanceUntilIdle()

            assertEquals("", viewModel.uiState.value.draft.relayMasqueClientCertificateChainPem)
            assertEquals("manual-private-key", viewModel.uiState.value.draft.relayMasqueClientPrivateKeyPem)
        }

    @Test
    fun `save snapshots one request and preserves edits made in flight`() =
        runTest {
            val appSettingsRepository = FakeAppSettingsRepository()
            val saveGate = CompletableDeferred<Unit>()
            val profileStore = ControllableRelayProfileStore(saveGate = saveGate)
            val viewModel =
                createConfigViewModel(
                    appSettingsRepository = appSettingsRepository,
                    relayProfileStore = profileStore,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDraft { copy(proxyPort = "1081") }
            viewModel.saveDraft()
            viewModel.saveDraft()
            runCurrent()

            assertEquals(1, profileStore.saveCalls)
            assertTrue(viewModel.uiState.value.isEditorSaving)

            viewModel.updateDraft { copy(proxyPort = "1082") }
            viewModel.saveDraft()
            saveGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, profileStore.saveCalls)
            assertEquals("1081", appSettingsRepository.snapshot().toConfigDraft().proxyPort)
            assertEquals("1082", viewModel.uiState.value.draft.proxyPort)
            assertTrue(viewModel.uiState.value.isEditorDirty)
            assertFalse(viewModel.uiState.value.isEditorSaving)

            viewModel.saveDraft()
            advanceUntilIdle()

            assertEquals(2, profileStore.saveCalls)
            assertEquals("1082", appSettingsRepository.snapshot().toConfigDraft().proxyPort)
        }

    @Test
    fun `cancel is atomically blocked while save is paused`() =
        runTest {
            val saveGate = CompletableDeferred<Unit>()
            val profileStore = ControllableRelayProfileStore(saveGate = saveGate)
            val appSettingsRepository = FakeAppSettingsRepository()
            val viewModel =
                createConfigViewModel(
                    appSettingsRepository = appSettingsRepository,
                    relayProfileStore = profileStore,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDraft { copy(proxyPort = "1081") }
            viewModel.saveDraft()
            val cancelled = viewModel.cancelEditing()
            val exitDecision = viewModel.requestEditorExit()
            runCurrent()

            assertFalse(cancelled)
            assertEquals(ConfigEditorExitDecision.Blocked, exitDecision)
            assertTrue(viewModel.uiState.value.isEditorSaving)
            assertTrue(viewModel.uiState.value.isEditorDirty)

            saveGate.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isEditorSaving)

            assertEquals(1, profileStore.saveCalls)
            assertEquals("1081", appSettingsRepository.snapshot().toConfigDraft().proxyPort)
            assertFalse(viewModel.uiState.value.isEditorDirty)
            assertFalse(viewModel.uiState.value.isEditorSaving)
        }

    @Test
    fun `config mode actions wait for the editor save and remain authoritative`() =
        runTest {
            val saveGate = CompletableDeferred<Unit>()
            val profileStore = ControllableRelayProfileStore(saveGate = saveGate)
            val appSettingsRepository = FakeAppSettingsRepository()
            val serviceController = FakeServiceController()
            val viewModel =
                createConfigViewModel(
                    appSettingsRepository = appSettingsRepository,
                    serviceController = serviceController,
                    relayProfileStore = profileStore,
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDraft { copy(mode = Mode.VPN, proxyPort = "1081") }
            viewModel.saveDraft()
            runCurrent()
            assertFalse(viewModel.cancelEditing())
            viewModel.selectMode(Mode.Proxy)
            viewModel.toggleRuntimeMode(Mode.Proxy, enabled = true)

            assertTrue(viewModel.uiState.value.isEditorSaving)
            assertTrue(serviceController.startedModes.isEmpty())

            saveGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(Mode.Proxy, appSettingsRepository.snapshot().toConfigDraft().mode)
            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
            assertFalse(viewModel.uiState.value.isEditorSaving)
        }

    @Test
    fun `failed save shows generic error and keeps retryable draft`() =
        runTest {
            val profileStore = ControllableRelayProfileStore(saveFailure = IllegalStateException("save failed"))
            val viewModel = createConfigViewModel(relayProfileStore = profileStore)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            val errorEffect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }

            viewModel.updateDraft { copy(proxyPort = "1081") }
            viewModel.saveDraft()
            advanceUntilIdle()

            val effect = errorEffect.await()
            assertEquals(ConfigEffect.Message("unexpected error"), effect)
            assertFalse((effect as ConfigEffect.Message).text.contains("save failed"))
            assertTrue(viewModel.uiState.value.isEditorDirty)
            assertFalse(viewModel.uiState.value.isEditorSaving)

            viewModel.saveDraft()
            advanceUntilIdle()

            assertEquals(2, profileStore.saveCalls)
            assertFalse(viewModel.uiState.value.isEditorDirty)
        }

    @Test
    fun `cancelled save clears single flight state and can be retried`() =
        runTest {
            val profileStore = ControllableRelayProfileStore(saveFailure = CancellationException("cancelled"))
            val viewModel = createConfigViewModel(relayProfileStore = profileStore)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDraft { copy(proxyPort = "1081") }
            viewModel.saveDraft()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isEditorDirty)
            assertFalse(viewModel.uiState.value.isEditorSaving)

            viewModel.saveDraft()
            advanceUntilIdle()

            assertEquals(2, profileStore.saveCalls)
            assertFalse(viewModel.uiState.value.isEditorDirty)
        }
}

private fun createConfigViewModel(
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    appSettingsRepository: AppSettingsRepository = FakeAppSettingsRepository(),
    serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
    serviceController: FakeServiceController = FakeServiceController(),
    relayProfileStore: RelayProfileStore = InMemoryRelayProfileStore(),
    relayCredentialStore: RelayCredentialStore = InMemoryRelayCredentialStore(),
    masqueClientCredentialImporter: MasqueClientCredentialImporter = NoOpMasqueClientCredentialImporter,
    editorDraftStore: ConfigEditorDraftStore = InMemoryConfigEditorDraftStore(),
    xrayNativeProviderSelection: XrayNativeProviderSelection =
        RecordingXrayNativeProviderSelection(appSettingsRepository),
): ConfigViewModel =
    ConfigViewModel(
        savedStateHandle = savedStateHandle,
        dependencies =
            ConfigViewModelDependencies(
                appSettingsRepository = appSettingsRepository,
                relayArtifacts =
                    ConfigRelayArtifactRepository(
                        appSettingsRepository = appSettingsRepository,
                        relayProfileStore = relayProfileStore,
                        relayCredentialStore = relayCredentialStore,
                    ),
                relayPresetCatalog = RelayPresetCatalog(RuntimeEnvironment.getApplication()),
                networkSnapshotProvider = FakeNativeNetworkSnapshotProvider(),
                serviceStateStore = serviceStateStore,
                serviceController = serviceController,
                latestDirectModeOutcomeStore = FakeLatestDirectModeOutcomeStore(),
                capabilityObserver =
                    ConfigCapabilityObserver(
                        networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                        serverCapabilityStore = NoOpServerCapabilityStore(),
                        dispatchers = testDispatchers(),
                    ),
                dispatchers = testDispatchers(),
                editorDraftStore = editorDraftStore,
                xrayNativeProviderSelection = xrayNativeProviderSelection,
            ),
        importDependencies =
            ConfigImportDependencies(
                masqueClientCredentialImporter = masqueClientCredentialImporter,
                masquePrivacyPassAvailability = NoOpMasquePrivacyPassAvailability,
            ),
        stringResolver = ResourceStringResolver(),
    )

internal class InMemoryConfigEditorDraftStore(
    private val invalidationFailure: Throwable? = null,
    var persistFailuresRemaining: Int = 0,
) : ConfigEditorDraftStore {
    private var recoverySessionId: String? = null
    private var session: ConfigEditorSession? = null
    private val invalidatedSessionIds = mutableSetOf<String>()
    var persistCalls: Int = 0
        private set

    override suspend fun restore(recoverySessionId: String): ConfigEditorDraftRestoreResult =
        if (recoverySessionId !in invalidatedSessionIds && this.recoverySessionId == recoverySessionId) {
            ConfigEditorDraftRestoreResult.Restored(requireNotNull(session))
        } else {
            ConfigEditorDraftRestoreResult.MissingOrInvalid
        }

    override suspend fun persist(
        recoverySessionId: String,
        session: ConfigEditorSession,
    ) {
        persistCalls += 1
        if (persistFailuresRemaining > 0) {
            persistFailuresRemaining -= 1
            throw java.io.IOException("fixture persistence failure")
        }
        if (recoverySessionId in invalidatedSessionIds) return
        this.recoverySessionId = recoverySessionId
        this.session = session
    }

    override suspend fun delete(recoverySessionId: String) {
        if (this.recoverySessionId == recoverySessionId) {
            this.recoverySessionId = null
            session = null
        }
    }

    override suspend fun invalidate(recoverySessionId: String) {
        invalidationFailure?.let { throw it }
        invalidatedSessionIds += recoverySessionId
        if (this.recoverySessionId == recoverySessionId) {
            this.recoverySessionId = null
            session = null
        }
    }
}

private class GatedRestoreConfigEditorDraftStore : ConfigEditorDraftStore {
    val restoreStarted = CompletableDeferred<Unit>()
    val restoreResult = CompletableDeferred<ConfigEditorDraftRestoreResult>()
    val deletedSessionIds = mutableListOf<String>()

    override suspend fun restore(recoverySessionId: String): ConfigEditorDraftRestoreResult {
        restoreStarted.complete(Unit)
        return restoreResult.await()
    }

    override suspend fun persist(
        recoverySessionId: String,
        session: ConfigEditorSession,
    ) = Unit

    override suspend fun delete(recoverySessionId: String) {
        deletedSessionIds += recoverySessionId
    }

    override suspend fun invalidate(recoverySessionId: String) {
        deletedSessionIds += recoverySessionId
    }
}

private class GatedUpdateAppSettingsRepository(
    private val updateGate: CompletableDeferred<Unit>,
    private val delegate: FakeAppSettingsRepository = FakeAppSettingsRepository(),
) : AppSettingsRepository by delegate {
    override suspend fun update(transform: com.poyka.ripdpi.proto.AppSettings.Builder.() -> Unit) {
        updateGate.await()
        delegate.update(transform)
    }
}

class ConfigViewModelRelayRecommendationTest {
    @Test
    fun `relay preset suggestion uses stored capability evidence when runtime is idle`() {
        val suggestion =
            resolveRelayPresetSuggestion(
                heuristicSuggestion =
                    RelayPresetSuggestion(
                        preset =
                            RelayPresetDefinition(
                                id = "ru-mobile-tuic",
                                title = "Russian mobile TUIC",
                                relayKind = RelayKindTuicV5,
                                routeMode = "direct_for_domestic",
                            ),
                        reason =
                            "Saved capability evidence for this network shows QUIC and UDP relay paths are usable.",
                    ),
                serviceTelemetry = ServiceTelemetrySnapshot(),
                capabilityRecords =
                    listOf(
                        ServerCapabilityRecord(
                            scope = "relay",
                            fingerprintHash = "fp",
                            authority = "relay.example",
                            quicUsable = true,
                            udpUsable = true,
                        ),
                    ),
            )

        assertTrue(suggestion?.reason?.contains("Saved capability evidence") == true)
    }

    @Test
    fun `relay preset suggestion uses transport remediation reason when provided`() {
        // When Direct-Mode evidence is fed in, the reason path
        // matches the same selector Diagnostics and Home use.
        val suggestion =
            resolveRelayPresetSuggestion(
                heuristicSuggestion =
                    RelayPresetSuggestion(
                        preset = RelayPresetDefinition(id = "ru-mobile-relay", title = "Russian mobile relay"),
                        reason = "heuristic only",
                    ),
                serviceTelemetry = ServiceTelemetrySnapshot(),
                capabilityRecords = emptyList(),
                transportRemediation = TransportRemediationKind.BROWSER_FALLBACK,
            )

        assertTrue(
            "browser-fallback reason should mention transparent-TLS interference",
            suggestion?.reason?.contains("transparent-TLS interference") == true,
        )
    }

    @Test
    fun `relay preset suggestion transport remediation overrides telemetry reason`() {
        // Direct-Mode evidence wins over telemetry heuristics so both
        // surfaces present a consistent recommendation.
        val suggestion =
            resolveRelayPresetSuggestion(
                heuristicSuggestion =
                    RelayPresetSuggestion(
                        preset = RelayPresetDefinition(id = "ru-mobile-relay", title = "Russian mobile relay"),
                        reason = "heuristic only",
                    ),
                serviceTelemetry =
                    ServiceTelemetrySnapshot(
                        status = AppStatus.Running,
                        runtimeFieldTelemetry =
                            RuntimeFieldTelemetry(
                                failureClass = RuntimeFailureClass.FingerprintPolicy,
                            ),
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                lastError = "whitelist_sni_failed",
                            ),
                    ),
                capabilityRecords = emptyList(),
                transportRemediation = TransportRemediationKind.QUIC_FALLBACK,
            )

        assertTrue(
            "QUIC-fallback reason should win over the whitelist-pressure heuristic",
            suggestion?.reason?.contains("TCP-only direct paths") == true,
        )
        assertFalse(
            "whitelist-pressure heuristic should not appear when direct-mode evidence is present",
            suggestion?.reason?.contains("whitelist-style routing pressure") == true,
        )
    }

    @Test
    fun `transport remediation kinds map to distinct preset reasons`() {
        // The four kinds must produce four distinct reason strings so the
        // Config surface mirrors the Diagnostics/Home recommendation exactly.
        val reasons =
            TransportRemediationKind
                .entries
                .map { it.toRelayPresetReason() }
                .toSet()
        assertEquals(TransportRemediationKind.entries.size, reasons.size)
        for (reason in reasons) {
            assertTrue("reason must start with 'Direct-mode': '$reason'", reason.startsWith("Direct-mode"))
        }
    }

    @Test
    fun `direct mode snapshot threads through to relay preset reason`() {
        // When the LatestDirectModeOutcomeStore emits an
        // OWNED_STACK_ONLY verdict, the snapshot fields feed
        // recommendTransportRemediation, whose kind feeds resolveRelayPresetSuggestion.
        // This is the exact chain ConfigViewModel.uiState now executes.
        val snapshot =
            LatestDirectModeOutcomeSnapshot(
                result = DirectModeVerdictResult.OWNED_STACK_ONLY,
                reasonCode = null,
                transportClass = null,
                recordedAt = 0L,
            )
        val kind =
            recommendTransportRemediation(
                result = snapshot.result,
                reasonCode = snapshot.reasonCode,
                transportClass = snapshot.transportClass,
            )
        assertEquals(TransportRemediationKind.OWNED_STACK_ACTION, kind)

        val suggestion =
            resolveRelayPresetSuggestion(
                heuristicSuggestion =
                    RelayPresetSuggestion(
                        preset = RelayPresetDefinition(id = "ru-mobile-relay", title = "Russian mobile relay"),
                        reason = "heuristic only",
                    ),
                serviceTelemetry = ServiceTelemetrySnapshot(),
                capabilityRecords = emptyList(),
                transportRemediation = kind,
            )
        assertTrue(
            "owned-stack reason must mention owned-stack",
            suggestion?.reason?.contains("owned-stack") == true,
        )
    }

    @Test
    fun `null direct mode snapshot leaves resolution to telemetry path`() {
        // When no diagnostics run has populated the store yet, the snapshot
        // is null and the existing telemetry / capability evidence path
        // remains in charge -- the wire-up must not regress that fallback.
        val kind =
            recommendTransportRemediation(
                result = null,
                reasonCode = null,
                transportClass = null,
            )
        assertEquals(null, kind)

        val suggestion =
            resolveRelayPresetSuggestion(
                heuristicSuggestion =
                    RelayPresetSuggestion(
                        preset = RelayPresetDefinition(id = "ru-mobile-relay", title = "Russian mobile relay"),
                        reason = "heuristic only",
                    ),
                serviceTelemetry = ServiceTelemetrySnapshot(),
                capabilityRecords = emptyList(),
                transportRemediation = kind,
            )
        // Without telemetry evidence, capability records, or a remediation
        // verdict, the resolver returns null.
        assertEquals(null, suggestion)
    }

    @Test
    fun `apply relay preset definition materializes chain profile references`() {
        val updated =
            defaultDraft.applyRelayPresetDefinition(
                RelayPresetDefinition(
                    id = "ru-mobile-relay",
                    title = "Russian mobile relay",
                    relayKind = RelayKindChainRelay,
                    chainEntryProfileId = "ru-mobile-entry",
                    chainExitProfileId = "eu-egress",
                ),
            )

        assertTrue(updated.relayEnabled)
        assertEquals(com.poyka.ripdpi.data.RelayKindChainRelay, updated.relayKind)
        assertEquals("ru-mobile-entry", updated.relayChainEntryProfileId)
        assertEquals("eu-egress", updated.relayChainExitProfileId)
    }

    @Test
    fun `legacy chain relay draft migrates inline hops into referenced profiles`() =
        runTest {
            val profileStore = InMemoryRelayProfileStore()
            val credentialStore = InMemoryRelayCredentialStore()

            val migrated =
                migrateLegacyChainRelayDraft(
                    draft =
                        defaultDraft.copy(
                            relayEnabled = true,
                            relayKind = RelayKindChainRelay,
                            relayProfileId = "legacy-chain",
                            relayChainEntryServer = "entry.example",
                            relayChainEntryPort = "443",
                            relayChainEntryServerName = "entry-sni.example",
                            relayChainEntryPublicKey = "entry-public",
                            relayChainEntryShortId = "entry-short",
                            relayChainEntryUuid = "11111111-1111-1111-1111-111111111111",
                            relayChainExitServer = "exit.example",
                            relayChainExitPort = "443",
                            relayChainExitServerName = "exit-sni.example",
                            relayChainExitPublicKey = "exit-public",
                            relayChainExitShortId = "exit-short",
                            relayChainExitUuid = "22222222-2222-2222-2222-222222222222",
                        ),
                    relayProfileStore = profileStore,
                    relayCredentialStore = credentialStore,
                )

            assertEquals("legacy-chain__ripdpi_chain_entry", migrated.relayChainEntryProfileId)
            assertEquals("legacy-chain__ripdpi_chain_exit", migrated.relayChainExitProfileId)
            assertEquals("", migrated.relayChainEntryServer)
            assertEquals("", migrated.relayChainExitServer)
            assertEquals("", migrated.relayChainEntryUuid)
            assertEquals("", migrated.relayChainExitUuid)

            assertEquals(
                RelayProfileRecord(
                    id = "legacy-chain__ripdpi_chain_entry",
                    kind = RelayKindVlessReality,
                    server = "entry.example",
                    serverPort = 443,
                    serverName = "entry-sni.example",
                    realityPublicKey = "entry-public",
                    realityShortId = "entry-short",
                ),
                profileStore.load("legacy-chain__ripdpi_chain_entry"),
            )
            assertEquals(
                "11111111-1111-1111-1111-111111111111",
                credentialStore.load("legacy-chain__ripdpi_chain_entry")?.vlessUuid,
            )
            assertEquals(
                "22222222-2222-2222-2222-222222222222",
                credentialStore.load("legacy-chain__ripdpi_chain_exit")?.vlessUuid,
            )
        }
}

internal class InMemoryRelayProfileStore : RelayProfileStore {
    private val records = LinkedHashMap<String, RelayProfileRecord>()

    override suspend fun load(profileId: String): RelayProfileRecord? = records[profileId]

    override suspend fun list(): List<RelayProfileRecord> = records.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        records[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}

private class ControllableRelayProfileStore(
    loadFailure: Throwable? = null,
    saveFailure: Throwable? = null,
    saveGate: CompletableDeferred<Unit>? = null,
) : RelayProfileStore {
    private val records = LinkedHashMap<String, RelayProfileRecord>()
    private var nextLoadFailure = loadFailure
    private var nextSaveFailure = saveFailure
    private var nextSaveGate = saveGate

    var saveCalls: Int = 0
        private set

    override suspend fun load(profileId: String): RelayProfileRecord? {
        nextLoadFailure?.let { error ->
            nextLoadFailure = null
            throw error
        }
        return records[profileId]
    }

    override suspend fun list(): List<RelayProfileRecord> = records.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        saveCalls += 1
        nextSaveGate?.let { gate ->
            nextSaveGate = null
            gate.await()
        }
        nextSaveFailure?.let { error ->
            nextSaveFailure = null
            throw error
        }
        records[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}

internal class InMemoryRelayCredentialStore : RelayCredentialStore {
    private val records = LinkedHashMap<String, RelayCredentialRecord>()

    override suspend fun load(profileId: String): RelayCredentialRecord? = records[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        records[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}

internal class RecordingXrayNativeProviderSelection(
    private val repository: AppSettingsRepository,
    private val failure: Throwable? = null,
) : XrayNativeProviderSelection {
    val selectedModes = mutableListOf<Mode>()

    override suspend fun selectNativeMode(mode: Mode) {
        failure?.let { throw it }
        selectedModes += mode
        repository.update { setRipdpiMode(mode.preferenceValue) }
    }
}

private class FakeNativeNetworkSnapshotProvider : NativeNetworkSnapshotProvider {
    override fun capture(): NativeNetworkSnapshot = NativeNetworkSnapshot()
}

internal fun testDispatchers(): AppCoroutineDispatchers =
    AppCoroutineDispatchers(
        default = Dispatchers.Main,
        io = Dispatchers.Main,
        main = Dispatchers.Main,
    )

internal class FakeNetworkFingerprintProvider : NetworkFingerprintProvider {
    override fun capture(): NetworkFingerprint? = null
}

internal class NoOpServerCapabilityStore : ServerCapabilityStore {
    override suspend fun relayCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord> =
        emptyList()

    override suspend fun directPathCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord> =
        emptyList()

    override suspend fun rememberRelayObservation(
        fingerprint: NetworkFingerprint,
        authority: String,
        relayProfileId: String?,
        observation: ServerCapabilityObservation,
        source: String,
        recordedAt: Long?,
    ): ServerCapabilityRecord =
        ServerCapabilityRecord(
            scope = "relay",
            fingerprintHash = fingerprint.scopeKey(),
            authority = authority,
            relayProfileId = relayProfileId,
        )

    override suspend fun rememberDirectPathObservation(
        fingerprint: NetworkFingerprint,
        authority: String,
        observation: ServerCapabilityObservation,
        source: String,
        recordedAt: Long?,
    ): ServerCapabilityRecord =
        ServerCapabilityRecord(
            scope = "direct_path",
            fingerprintHash = fingerprint.scopeKey(),
            authority = authority,
        )

    override suspend fun clearAll() = Unit
}

internal object NoOpMasqueClientCredentialImporter : MasqueClientCredentialImporter {
    override suspend fun importCertificateChainPem(uri: Uri): String = ""

    override suspend fun importPrivateKeyPem(uri: Uri): String = ""

    override suspend fun importPkcs12Identity(
        uri: Uri,
        password: String?,
    ): ImportedMasqueClientIdentity = ImportedMasqueClientIdentity(certificateChainPem = "", privateKeyPem = "")
}

private class ControllableMasqueClientCredentialImporter(
    private val gate: CompletableDeferred<Unit>,
) : MasqueClientCredentialImporter {
    var callCount: Int = 0
        private set

    override suspend fun importCertificateChainPem(uri: Uri): String {
        callCount += 1
        gate.await()
        return sampleCertificatePem()
    }

    override suspend fun importPrivateKeyPem(uri: Uri): String {
        callCount += 1
        gate.await()
        return samplePrivateKeyPem()
    }

    override suspend fun importPkcs12Identity(
        uri: Uri,
        password: String?,
    ): ImportedMasqueClientIdentity {
        callCount += 1
        gate.await()
        return ImportedMasqueClientIdentity(sampleCertificatePem(), samplePrivateKeyPem())
    }
}

private class OrderedMasqueClientCredentialImporter(
    certificateResults: List<CompletableDeferred<String>> = emptyList(),
    pkcs12Results: List<CompletableDeferred<ImportedMasqueClientIdentity>> = emptyList(),
) : MasqueClientCredentialImporter {
    private val certificates = ArrayDeque(certificateResults)
    private val pkcs12Identities = ArrayDeque(pkcs12Results)

    override suspend fun importCertificateChainPem(uri: Uri): String = certificates.removeFirst().await()

    override suspend fun importPrivateKeyPem(uri: Uri): String = error("Unexpected private key import")

    override suspend fun importPkcs12Identity(
        uri: Uri,
        password: String?,
    ): ImportedMasqueClientIdentity = pkcs12Identities.removeFirst().await()
}

internal object NoOpMasquePrivacyPassAvailability : MasquePrivacyPassAvailability {
    override fun isAvailable(): Boolean = false

    override fun buildStatus(): MasquePrivacyPassBuildStatus = MasquePrivacyPassBuildStatus.MissingProviderUrl
}
