package com.poyka.ripdpi.activities

import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.FakeTlsSniModeRandomized
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.proto.StrategyTcpStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStateTest {
    private val defaults = AppSettingsSerializer.defaultValue

    @Test
    fun `default settings produce correct ui state`() {
        val state = defaults.toUiState()
        assertTrue(state.isVpn)
        assertFalse(state.useCmdSettings)
        assertEquals("disorder", state.desyncMethod)
        assertFalse(state.hostAutolearnEnabled)
        assertEquals(DefaultHostAutolearnPenaltyTtlHours, state.hostAutolearnPenaltyTtlHours)
        assertEquals(DefaultHostAutolearnMaxHosts, state.hostAutolearnMaxHosts)
        assertTrue(state.desyncEnabled)
        assertFalse(state.isFake)
        assertFalse(state.usesFakeTransport)
        assertFalse(state.hasHostFake)
        assertEquals(0, state.hostFakeStepCount)
        assertTrue(state.hostFakeControlsRelevant)
        assertTrue(state.showHostFakeProfile)
        assertFalse(state.isOob)
        assertEquals(FakeTlsSniModeFixed, state.fakeTlsSniMode)
        assertEquals(0, state.fakeTlsSize)
        assertFalse(state.fakeTlsControlsRelevant)
        assertFalse(state.hasCustomFakeTlsProfile)
        assertFalse(state.canResetFakeTlsProfile)
        assertTrue(state.desyncHttpEnabled)
        assertTrue(state.desyncHttpsEnabled)
        assertFalse(state.desyncUdpEnabled)
        assertEquals(QuicFakeProfileDisabled, state.quicFakeProfile)
        assertEquals("", state.quicFakeHost)
        assertFalse(state.quicFakeProfileActive)
        assertFalse(state.hasUdpFakeBurst)
        assertFalse(state.quicFakeControlsRelevant)
        assertFalse(state.showQuicFakeHostOverride)
        assertFalse(state.quicFakeUsesCustomHost)
        assertEquals("", state.quicFakeEffectiveHost)
        assertFalse(state.showQuicFakeProfile)
        assertFalse(state.tlsRecEnabled)
        assertFalse(state.webrtcProtectionEnabled)
        assertFalse(state.biometricEnabled)
        assertEquals("", state.backupPin)
        assertFalse(state.hasBackupPin)
        assertEquals(LauncherIconManager.DefaultIconKey, state.appIconVariant)
        assertTrue(state.themedAppIconEnabled)
        assertEquals(DefaultSplitMarker, state.splitMarker)
        assertTrue(state.diagnosticsMonitorEnabled)
        assertEquals(15, state.diagnosticsSampleIntervalSeconds)
        assertEquals(14, state.diagnosticsHistoryRetentionDays)
        assertTrue(state.diagnosticsExportIncludeHistory)
    }

    @Test
    fun `proxy mode sets isVpn false`() {
        val settings = defaults.toBuilder().setRipdpiMode("proxy").build()
        assertFalse(settings.toUiState().isVpn)
    }

    @Test
    fun `empty mode defaults to vpn`() {
        val settings = defaults.toBuilder().setRipdpiMode("").build()
        assertTrue(settings.toUiState().isVpn)
    }

    @Test
    fun `desync method none disables desync`() {
        val settings = defaults.toBuilder().setDesyncMethod("none").build()
        val state = settings.toUiState()
        assertFalse(state.desyncEnabled)
        assertFalse(state.isFake)
        assertFalse(state.isOob)
    }

    @Test
    fun `desync method fake sets isFake`() {
        val settings = defaults.toBuilder().setDesyncMethod("fake").build()
        val state = settings.toUiState()
        assertTrue(state.isFake)
        assertTrue(state.usesFakeTransport)
        assertTrue(state.desyncEnabled)
    }

    @Test
    fun `hostfake step enables fake transport without fake tls controls`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("hostfake")
                        .setMarker("endhost+8")
                        .setMidhostMarker("midsld")
                        .setFakeHostTemplate("googlevideo.com")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertFalse(state.isFake)
        assertTrue(state.usesFakeTransport)
        assertTrue(state.hasHostFake)
        assertEquals(1, state.hostFakeStepCount)
        assertEquals("endhost+8", state.primaryHostFakeStep?.marker)
        assertEquals("midsld", state.primaryHostFakeStep?.midhostMarker)
        assertEquals("googlevideo.com", state.primaryHostFakeStep?.fakeHostTemplate)
        assertFalse(state.fakeTlsControlsRelevant)
        assertEquals("tcp: hostfake(endhost+8 midhost=midsld host=googlevideo.com)", state.chainSummary)
    }

    @Test
    fun `hostfake guidance hides when no relevant protocols are enabled`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .build()

        val state = settings.toUiState()

        assertFalse(state.hostFakeControlsRelevant)
        assertFalse(state.showHostFakeProfile)
    }

    @Test
    fun `hostfake profile remains visible in command line mode`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertFalse(state.hostFakeControlsRelevant)
        assertTrue(state.showHostFakeProfile)
    }

    @Test
    fun `desync method oob sets isOob`() {
        val settings = defaults.toBuilder().setDesyncMethod("oob").build()
        assertTrue(settings.toUiState().isOob)
    }

    @Test
    fun `desync method disoob sets isOob`() {
        val settings = defaults.toBuilder().setDesyncMethod("disoob").build()
        assertTrue(settings.toUiState().isOob)
    }

    @Test
    fun `all protocols unchecked enables all`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(false)
                .build()
        val state = settings.toUiState()
        assertTrue(state.desyncHttpEnabled)
        assertTrue(state.desyncHttpsEnabled)
        assertTrue(state.desyncUdpEnabled)
    }

    @Test
    fun `only http checked enables only http`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(true)
                .setDesyncHttps(false)
                .setDesyncUdp(false)
                .build()
        val state = settings.toUiState()
        assertTrue(state.desyncHttpEnabled)
        assertFalse(state.desyncHttpsEnabled)
        assertFalse(state.desyncUdpEnabled)
    }

    @Test
    fun `tlsrec enabled only when https enabled and toggle on`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttps(true)
                .setTlsrecEnabled(true)
                .build()
        assertTrue(settings.toUiState().tlsRecEnabled)
    }

    @Test
    fun `tlsrec disabled when https disabled even if toggle on`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(true)
                .setDesyncHttps(false)
                .setDesyncUdp(false)
                .setTlsrecEnabled(true)
                .build()
        assertFalse(settings.toUiState().tlsRecEnabled)
    }

    @Test
    fun `cmd settings enabled`() {
        val settings = defaults.toBuilder().setEnableCmdSettings(true).build()
        assertTrue(settings.toUiState().useCmdSettings)
    }

    @Test
    fun `empty desync method defaults to disorder`() {
        val settings = defaults.toBuilder().setDesyncMethod("").build()
        assertEquals("disorder", settings.toUiState().desyncMethod)
    }

    @Test
    fun `legacy split settings map into effective marker`() {
        val settings =
            defaults
                .toBuilder()
                .setSplitMarker("")
                .setSplitPosition(2)
                .setSplitAtHost(true)
                .build()

        assertEquals("host+2", settings.toUiState().splitMarker)
    }

    @Test
    fun `backup pin presence is derived from stored value`() {
        val settings = defaults.toBuilder().setBackupPin("1234").build()
        assertTrue(settings.toUiState().hasBackupPin)
    }

    @Test
    fun `empty app icon variant defaults to launcher default`() {
        val settings = defaults.toBuilder().setAppIconVariant("").build()
        assertEquals(LauncherIconManager.DefaultIconKey, settings.toUiState().appIconVariant)
    }

    @Test
    fun `plain app icon style disables themed icon support`() {
        val settings = defaults.toBuilder().setAppIconStyle(LauncherIconManager.PlainIconStyle).build()
        assertFalse(settings.toUiState().themedAppIconEnabled)
    }

    @Test
    fun `blank app icon style normalizes to themed`() {
        val settings = defaults.toBuilder().setAppIconStyle("").build()
        assertTrue(settings.toUiState().themedAppIconEnabled)
    }

    @Test
    fun `diagnostics history settings preserve stored values`() {
        val settings =
            defaults
                .toBuilder()
                .setDiagnosticsMonitorEnabled(false)
                .setDiagnosticsSampleIntervalSeconds(45)
                .setDiagnosticsHistoryRetentionDays(30)
                .setDiagnosticsExportIncludeHistory(false)
                .build()

        val state = settings.toUiState()

        assertFalse(state.diagnosticsMonitorEnabled)
        assertEquals(45, state.diagnosticsSampleIntervalSeconds)
        assertEquals(30, state.diagnosticsHistoryRetentionDays)
        assertFalse(state.diagnosticsExportIncludeHistory)
    }

    @Test
    fun `ui state can remain unhydrated until datastore emits`() {
        val state = defaults.toUiState(isHydrated = false)
        assertFalse(state.isHydrated)
        assertFalse(state.biometricEnabled)
    }

    @Test
    fun `host autolearn values normalize from stored settings`() {
        val settings =
            defaults
                .toBuilder()
                .setHostAutolearnEnabled(true)
                .setHostAutolearnPenaltyTtlHours(0)
                .setHostAutolearnMaxHosts(0)
                .build()

        val state = settings.toUiState()

        assertTrue(state.hostAutolearnEnabled)
        assertEquals(DefaultHostAutolearnPenaltyTtlHours, state.hostAutolearnPenaltyTtlHours)
        assertEquals(DefaultHostAutolearnMaxHosts, state.hostAutolearnMaxHosts)
    }

    @Test
    fun `host autolearn runtime state is reflected in ui state`() {
        val state =
            defaults.toUiState(
                serviceStatus = AppStatus.Running,
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        autolearnEnabled = true,
                        learnedHostCount = 7,
                        penalizedHostCount = 2,
                        lastAutolearnHost = "example.org",
                        lastAutolearnGroup = 3,
                        lastAutolearnAction = "host_promoted",
                    ),
                hostAutolearnStorePresent = true,
            )

        assertTrue(state.isServiceRunning)
        assertTrue(state.hostAutolearnRuntimeEnabled)
        assertTrue(state.hostAutolearnStorePresent)
        assertEquals(7, state.hostAutolearnLearnedHostCount)
        assertEquals(2, state.hostAutolearnPenalizedHostCount)
        assertEquals("example.org", state.hostAutolearnLastHost)
        assertEquals(3, state.hostAutolearnLastGroup)
        assertEquals("host_promoted", state.hostAutolearnLastAction)
    }

    @Test
    fun `fake tls profile is exposed in ui state`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("fake")
                .setFakeTlsUseOriginal(true)
                .setFakeTlsRandomize(true)
                .setFakeTlsDupSessionId(true)
                .setFakeTlsPadEncap(true)
                .setFakeTlsSize(-24)
                .setFakeTlsSniMode(FakeTlsSniModeRandomized)
                .build()

        val state = settings.toUiState()

        assertTrue(state.isFake)
        assertTrue(state.fakeTlsControlsRelevant)
        assertTrue(state.hasCustomFakeTlsProfile)
        assertEquals(FakeTlsSniModeRandomized, state.fakeTlsSniMode)
        assertEquals(-24, state.fakeTlsSize)
        assertTrue(state.fakeTlsUseOriginal)
        assertTrue(state.fakeTlsRandomize)
        assertTrue(state.fakeTlsDupSessionId)
        assertTrue(state.fakeTlsPadEncap)
        assertTrue(state.canResetFakeTlsProfile)
    }

    @Test
    fun `quic fake profile is exposed when udp desync is enabled`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .setUdpFakeCount(3)
                .setQuicFakeProfile(QuicFakeProfileRealisticInitial)
                .setQuicFakeHost("video.example.test")
                .build()

        val state = settings.toUiState()

        assertTrue(state.desyncUdpEnabled)
        assertTrue(state.quicFakeControlsRelevant)
        assertTrue(state.hasUdpFakeBurst)
        assertTrue(state.quicFakeProfileActive)
        assertTrue(state.showQuicFakeProfile)
        assertTrue(state.showQuicFakeHostOverride)
        assertTrue(state.quicFakeUsesCustomHost)
        assertEquals("video.example.test", state.quicFakeEffectiveHost)
        assertEquals(QuicFakeProfileRealisticInitial, state.quicFakeProfile)
        assertEquals("video.example.test", state.quicFakeHost)
    }

    @Test
    fun `realistic quic fake profile falls back to built in host label`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .setQuicFakeProfile(QuicFakeProfileRealisticInitial)
                .build()

        val state = settings.toUiState()

        assertTrue(state.showQuicFakeHostOverride)
        assertFalse(state.quicFakeUsesCustomHost)
        assertEquals(DefaultQuicFakeHost, state.quicFakeEffectiveHost)
    }

    @Test
    fun `custom fixed fake sni counts as fake tls profile`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("fake")
                .setFakeSni("alt.example.org")
                .build()

        val state = settings.toUiState()

        assertTrue(state.fakeTlsControlsRelevant)
        assertTrue(state.hasCustomFakeTlsProfile)
        assertTrue(state.canResetFakeTlsProfile)
        assertEquals("alt.example.org", state.fakeSni)
        assertTrue(DefaultFakeSni != state.fakeSni)
    }

    @Test
    fun `forget learned hosts action is unavailable in command line mode`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .build()

        val state = settings.toUiState(hostAutolearnStorePresent = true)

        assertFalse(state.canForgetLearnedHosts)
    }
}
