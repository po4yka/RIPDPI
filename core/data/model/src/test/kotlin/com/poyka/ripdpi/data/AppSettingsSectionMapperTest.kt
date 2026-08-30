package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [AppSettings.toSettingsSections] — the one proto-to-section mapper.
 *
 * The mapper is a verbatim field copy, so these tests assert whole-section
 * equality: every field a representative `AppSettings` payload carries lands in
 * the matching section unchanged, and an all-default proto produces all-default
 * sections. A drift here is a drift in every downstream consumer's input.
 */
class AppSettingsSectionMapperTest {
    @Test
    fun `a default AppSettings maps to all-default sections`() {
        val sections = AppSettings.getDefaultInstance().toSettingsSections()

        assertEquals(
            SettingsSections(
                proxy = ProxySettingsSection(),
                wsTunnel = WsTunnelSettingsSection(),
                dns = DnsSettingsSection(),
                diagnostics = DiagnosticsSettingsSection(),
                relay = RelaySettingsSection(),
                warp = WarpSettingsSection(),
                strategy = StrategySettingsSection(),
                root = RootSettingsSection(),
                detection = DetectionSettingsSection(),
                telemetry = TelemetrySettingsSection(),
            ),
            sections,
        )
    }

    @Test
    fun `populated AppSettings projects the proxy and dns sections`() {
        val sections = populatedSettings().toSettingsSections()

        assertEquals(
            ProxySettingsSection(
                ripdpiMode = "proxy",
                ipv6Enable = true,
                proxyPort = 1080,
                tcpFastOpen = true,
                defaultTtl = 64,
                customTtl = true,
                freezeDetectionEnabled = true,
                mixedInboundEnabled = true,
                proxyAllowLan = true,
                lanAuthToken = "lan-tok",
            ),
            sections.proxy,
        )
        assertEquals(
            DnsSettingsSection(
                dnsMode = "encrypted",
                encryptedDnsPort = 853,
                encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            ),
            sections.dns,
        )
        assertEquals(
            WsTunnelSettingsSection(
                workerUrl = "https://edge.example.workers.dev/relay",
                workerCredentialRef = "workers-default",
            ),
            sections.wsTunnel,
        )
    }

    @Test
    fun `populated AppSettings projects the diagnostics, relay, and warp sections`() {
        val sections = populatedSettings().toSettingsSections()

        assertEquals(
            DiagnosticsSettingsSection(
                diagnosticsSampleIntervalSeconds = 30,
                diagnosticsAutoResumeAfterRawScan = true,
            ),
            sections.diagnostics,
        )
        assertEquals(
            RelaySettingsSection(
                relayEnabled = true,
                relayKind = "vless_reality",
                relayServerPort = 443,
                relayUdpEnabled = true,
                relayAppsScriptScriptIds = listOf("id-a", "id-b"),
            ),
            sections.relay,
        )
        assertEquals(
            WarpSettingsSection(warpEnabled = true, warpAmneziaJc = 4, warpAmneziaH1 = 123_456_789L),
            sections.warp,
        )
    }

    @Test
    fun `populated AppSettings projects the strategy, root, detection, and telemetry sections`() {
        val sections = populatedSettings().toSettingsSections()

        assertEquals(
            StrategySettingsSection(
                strategyEvolution = true,
                evolutionEpsilon = 0.25,
                evolutionExperimentTtlMs = 45_000L,
                strategyPackChannel = "beta",
            ),
            sections.strategy,
        )
        assertEquals(RootSettingsSection(rootModeEnabled = true), sections.root)
        assertEquals(
            DetectionSettingsSection(
                detectionCheckIncludeBypass = true,
                detectionCheckTunProbeMode = "strict_same_path",
                dpiSuiteConcurrency = 50,
            ),
            sections.detection,
        )
        assertEquals(
            TelemetrySettingsSection(
                communityComparisonEnabled = true,
                communityApiUrl = "https://example.test/api",
            ),
            sections.telemetry,
        )
    }

    @Test
    fun `appendHttpProxy is mapped from proto to proxy section`() {
        val sections =
            AppSettings
                .newBuilder()
                .setAppendHttpProxy(true)
                .build()
                .toSettingsSections()

        assertEquals(true, sections.proxy.appendHttpProxy)
    }

    private fun populatedSettings(): AppSettings =
        AppSettings
            .newBuilder()
            .setRipdpiMode("proxy")
            .setIpv6Enable(true)
            .setProxyPort(1080)
            .setTcpFastOpen(true)
            .setMixedInboundEnabled(true)
            .setDefaultTtl(64)
            .setCustomTtl(true)
            .setFreezeDetectionEnabled(true)
            .setProxyAllowLan(true)
            .setProxyLanAuthToken("lan-tok")
            .setWsTunnelWorkerUrl("https://edge.example.workers.dev/relay")
            .setWsTunnelWorkerCredentialRef("workers-default")
            .setDnsMode("encrypted")
            .setEncryptedDnsPort(853)
            .addAllEncryptedDnsBootstrapIps(listOf("1.1.1.1", "1.0.0.1"))
            .setDiagnosticsSampleIntervalSeconds(30)
            .setDiagnosticsAutoResumeAfterRawScan(true)
            .setRelayEnabled(true)
            .setRelayKind("vless_reality")
            .setRelayServerPort(443)
            .setRelayUdpEnabled(true)
            .addAllRelayAppsScriptScriptIds(listOf("id-a", "id-b"))
            .setWarpEnabled(true)
            .setWarpAmneziaJc(4)
            .setWarpAmneziaH1(123_456_789L)
            .setStrategyEvolution(true)
            .setEvolutionEpsilon(0.25)
            .setEvolutionExperimentTtlMs(45_000L)
            .setStrategyPackChannel("beta")
            .setRootModeEnabled(true)
            .setDetectionCheckIncludeBypass(true)
            .setDetectionCheckTunProbeMode("strict_same_path")
            .setDpiSuiteConcurrency(50)
            .setCommunityComparisonEnabled(true)
            .setCommunityApiUrl("https://example.test/api")
            .build()
}
