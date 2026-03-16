package com.poyka.ripdpi.activities

import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerEndHost
import com.poyka.ripdpi.data.AdaptiveMarkerHost
import com.poyka.ripdpi.data.AdaptiveMarkerMethod
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.FakeTlsSniModeRandomized
import com.poyka.ripdpi.data.QuicFakeProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.TlsFakeProfileGoogleChrome
import com.poyka.ripdpi.data.UdpFakeProfileDnsQuery
import com.poyka.ripdpi.proto.ActivationFilter
import com.poyka.ripdpi.proto.NumericRange
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
        assertFalse(state.hasFakeApproximation)
        assertEquals(0, state.fakeApproximationStepCount)
        assertTrue(state.fakeApproximationControlsRelevant)
        assertTrue(state.showFakeApproximationProfile)
        assertFalse(state.isOob)
        assertEquals(FakeTlsSniModeFixed, state.fakeTlsSniMode)
        assertEquals(0, state.fakeTlsSize)
        assertEquals(FakePayloadProfileCompatDefault, state.httpFakeProfile)
        assertEquals(FakePayloadProfileCompatDefault, state.tlsFakeProfile)
        assertEquals(FakePayloadProfileCompatDefault, state.udpFakeProfile)
        assertFalse(state.hasCustomFakePayloadProfiles)
        assertFalse(state.canResetFakePayloadLibrary)
        assertFalse(state.httpFakeProfileActiveInStrategy)
        assertFalse(state.tlsFakeProfileActiveInStrategy)
        assertFalse(state.udpFakeProfileActiveInStrategy)
        assertTrue(state.fakePayloadLibraryControlsRelevant)
        assertTrue(state.showFakePayloadLibrary)
        assertFalse(state.fakeTlsControlsRelevant)
        assertFalse(state.hasCustomFakeTlsProfile)
        assertFalse(state.canResetFakeTlsProfile)
        assertFalse(state.adaptiveFakeTtlEnabled)
        assertEquals(DefaultAdaptiveFakeTtlDelta, state.adaptiveFakeTtlDelta)
        assertEquals(DefaultAdaptiveFakeTtlMin, state.adaptiveFakeTtlMin)
        assertEquals(DefaultAdaptiveFakeTtlMax, state.adaptiveFakeTtlMax)
        assertEquals(DefaultAdaptiveFakeTtlFallback, state.adaptiveFakeTtlFallback)
        assertEquals(AdaptiveFakeTtlModeFixed, state.adaptiveFakeTtlMode)
        assertFalse(state.hasAdaptiveFakeTtl)
        assertFalse(state.hasCustomAdaptiveFakeTtl)
        assertFalse(state.showAdaptiveFakeTtlProfile)
        assertFalse(state.canResetAdaptiveFakeTtlProfile)
        assertTrue(state.desyncHttpEnabled)
        assertTrue(state.desyncHttpsEnabled)
        assertFalse(state.desyncUdpEnabled)
        assertFalse(state.httpMethodEol)
        assertFalse(state.httpUnixEol)
        assertFalse(state.hasSafeHttpParserTweaks)
        assertFalse(state.hasAggressiveHttpParserEvasions)
        assertFalse(state.hasCustomHttpParserEvasions)
        assertFalse(state.canResetHttpParserEvasions)
        assertTrue(state.httpParserControlsRelevant)
        assertTrue(state.showHttpParserProfile)
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
        assertEquals("disabled", state.tlsPreludeMode)
        assertEquals(0, state.tlsPreludeStepCount)
        assertEquals(DefaultTlsRandRecFragmentCount, state.tlsRandRecFragmentCount)
        assertEquals(DefaultTlsRandRecMinFragmentSize, state.tlsRandRecMinFragmentSize)
        assertEquals(DefaultTlsRandRecMaxFragmentSize, state.tlsRandRecMaxFragmentSize)
        assertTrue(state.tlsPreludeControlsRelevant)
        assertTrue(state.showTlsPreludeProfile)
        assertFalse(state.tlsPreludeUsesRandomRecords)
        assertFalse(state.hasStackedTlsPreludeSteps)
        assertTrue(state.showActivationWindowProfile)
        assertFalse(state.canResetActivationWindow)
        assertFalse(state.hasStepActivationFilters)
        assertEquals(0, state.stepActivationFilterCount)
        assertFalse(state.webrtcProtectionEnabled)
        assertFalse(state.biometricEnabled)
        assertEquals("", state.backupPinHash)
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
        assertTrue(state.httpFakeProfileActiveInStrategy)
        assertTrue(state.tlsFakeProfileActiveInStrategy)
        assertFalse(state.udpFakeProfileActiveInStrategy)
        assertTrue(state.desyncEnabled)
    }

    @Test
    fun `aggressive http parser evasions round trip into ui state`() {
        val settings =
            defaults
                .toBuilder()
                .setHostMixedCase(true)
                .setDomainMixedCase(true)
                .setHostRemoveSpaces(true)
                .setHttpMethodEol(true)
                .setHttpUnixEol(true)
                .build()

        val state = settings.toUiState()

        assertTrue(state.hostMixedCase)
        assertTrue(state.domainMixedCase)
        assertTrue(state.hostRemoveSpaces)
        assertTrue(state.httpMethodEol)
        assertTrue(state.httpUnixEol)
        assertTrue(state.hasSafeHttpParserTweaks)
        assertTrue(state.hasAggressiveHttpParserEvasions)
        assertTrue(state.hasCustomHttpParserEvasions)
        assertEquals(3, state.httpParserSafeCount)
        assertEquals(2, state.httpParserAggressiveCount)
        assertTrue(state.canResetHttpParserEvasions)
    }

    @Test
    fun `http parser profile remains visible in command line mode`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .setDesyncHttp(false)
                .setHostMixedCase(true)
                .setHttpMethodEol(true)
                .build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertFalse(state.httpParserControlsRelevant)
        assertTrue(state.hasCustomHttpParserEvasions)
        assertTrue(state.showHttpParserProfile)
        assertFalse(state.canResetHttpParserEvasions)
    }

    @Test
    fun `saved http parser evasions stay visible when http desync is off`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("none")
                .setDesyncHttp(false)
                .setDesyncHttps(true)
                .setHostRemoveSpaces(true)
                .build()

        val state = settings.toUiState()

        assertFalse(state.httpParserControlsRelevant)
        assertTrue(state.hasCustomHttpParserEvasions)
        assertTrue(state.showHttpParserProfile)
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
        assertFalse(state.httpFakeProfileActiveInStrategy)
        assertFalse(state.tlsFakeProfileActiveInStrategy)
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
    fun `fake approximation steps activate fake payload and fake tls controls`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttps(true)
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fakedsplit")
                        .setMarker("host+1")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.isFake)
        assertTrue(state.usesFakeTransport)
        assertTrue(state.httpFakeProfileActiveInStrategy)
        assertTrue(state.tlsFakeProfileActiveInStrategy)
        assertTrue(state.fakeTlsControlsRelevant)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertTrue(state.hasFakeApproximation)
        assertEquals(1, state.fakeApproximationStepCount)
        assertTrue(state.hasFakeSplitApproximation)
        assertFalse(state.hasFakeDisorderApproximation)
        assertEquals(TcpChainStepKind.FakeSplit, state.primaryFakeApproximationStep?.kind)
        assertEquals("host+1", state.primaryFakeApproximationStep?.marker)
        assertTrue(state.showFakeApproximationProfile)
    }

    @Test
    fun `fakeddisorder keeps adaptive fake ttl relevant`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fakeddisorder")
                        .setMarker("endhost")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.isFake)
        assertTrue(state.usesFakeTransport)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertTrue(state.hasFakeApproximation)
        assertFalse(state.hasFakeSplitApproximation)
        assertTrue(state.hasFakeDisorderApproximation)
        assertEquals(TcpChainStepKind.FakeDisorder, state.primaryFakeApproximationStep?.kind)
        assertEquals("tcp: fakeddisorder(endhost)", state.chainSummary)
    }

    @Test
    fun `fake approximation guidance hides when no relevant protocols are enabled`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .build()

        val state = settings.toUiState()

        assertFalse(state.fakeApproximationControlsRelevant)
        assertFalse(state.showFakeApproximationProfile)
    }

    @Test
    fun `fake approximation profile remains visible in command line mode`() {
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
        assertFalse(state.fakeApproximationControlsRelevant)
        assertTrue(state.showFakeApproximationProfile)
    }

    @Test
    fun `multiple fake approximation steps preserve primary step ordering`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fakedsplit")
                        .setMarker("host+1")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fakeddisorder")
                        .setMarker("endhost")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.hasFakeApproximation)
        assertEquals(2, state.fakeApproximationStepCount)
        assertTrue(state.hasFakeSplitApproximation)
        assertTrue(state.hasFakeDisorderApproximation)
        assertEquals(TcpChainStepKind.FakeSplit, state.primaryFakeApproximationStep?.kind)
        assertEquals("host+1", state.primaryFakeApproximationStep?.marker)
    }

    @Test
    fun `saved fake approximation stays visible when http and https are off`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fakedsplit")
                        .setMarker("host+1")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.hasFakeApproximation)
        assertTrue(state.isFake)
        assertFalse(state.fakeApproximationControlsRelevant)
        assertTrue(state.showFakeApproximationProfile)
        assertFalse(state.httpFakeProfileActiveInStrategy)
        assertFalse(state.tlsFakeProfileActiveInStrategy)
        assertFalse(state.fakeTlsControlsRelevant)
    }

    @Test
    fun `primary fake approximation step ignores tls prelude and hostfake steps ahead of it`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrec")
                        .setMarker("extlen")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("hostfake")
                        .setMarker("endhost+8")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fakeddisorder")
                        .setMarker("host+1")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fakedsplit")
                        .setMarker("endhost")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.hasHostFake)
        assertTrue(state.hasFakeApproximation)
        assertEquals(2, state.fakeApproximationStepCount)
        assertEquals(TcpChainStepKind.FakeDisorder, state.primaryFakeApproximationStep?.kind)
        assertEquals("host+1", state.primaryFakeApproximationStep?.marker)
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
    fun `adaptive split preset reflects known custom and unsupported primary markers`() {
        val balancedState =
            defaults
                .toBuilder()
                .setDesyncMethod("split")
                .setSplitMarker(AdaptiveMarkerBalanced)
                .build()
                .toUiState()
        val customState =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker(AdaptiveMarkerMethod)
                        .build(),
                ).build()
                .toUiState()
        val hostfakeState =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("hostfake")
                        .setMarker("endhost+8")
                        .build(),
                ).build()
                .toUiState()

        assertEquals(AdaptiveMarkerBalanced, balancedState.adaptiveSplitPreset)
        assertTrue(balancedState.hasAdaptiveSplitPreset)
        assertFalse(balancedState.hasCustomAdaptiveSplitPreset)
        assertTrue(balancedState.canResetAdaptiveSplitPreset)
        assertEquals(AdaptiveSplitPresetCustom, customState.adaptiveSplitPreset)
        assertTrue(customState.hasAdaptiveSplitPreset)
        assertTrue(customState.hasCustomAdaptiveSplitPreset)
        assertTrue(customState.canResetAdaptiveSplitPreset)
        assertFalse(hostfakeState.adaptiveSplitVisualEditorSupported)
        assertFalse(hostfakeState.canResetAdaptiveSplitPreset)
    }

    @Test
    fun `adaptive split visual editor follows first non tls tcp step`() {
        val settings =
            defaults
                .toBuilder()
                .setSplitMarker(AdaptiveMarkerBalanced)
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrec")
                        .setMarker("host+1")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker(AdaptiveMarkerEndHost)
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertEquals(AdaptiveMarkerEndHost, state.splitMarker)
        assertEquals(AdaptiveMarkerEndHost, state.adaptiveSplitPreset)
        assertTrue(state.hasAdaptiveSplitPreset)
        assertFalse(state.hasCustomAdaptiveSplitPreset)
        assertTrue(state.adaptiveSplitVisualEditorSupported)
    }

    @Test
    fun `manual adaptive split state does not expose reset`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker("host+2")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertEquals("host+2", state.splitMarker)
        assertEquals(AdaptiveSplitPresetManual, state.adaptiveSplitPreset)
        assertFalse(state.hasAdaptiveSplitPreset)
        assertFalse(state.hasCustomAdaptiveSplitPreset)
        assertFalse(state.canResetAdaptiveSplitPreset)
        assertTrue(state.adaptiveSplitVisualEditorSupported)
    }

    @Test
    fun `command line mode keeps adaptive split preset visible but disables reset`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .setDesyncMethod("split")
                .setSplitMarker(AdaptiveMarkerHost)
                .build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertEquals(AdaptiveMarkerHost, state.adaptiveSplitPreset)
        assertTrue(state.hasAdaptiveSplitPreset)
        assertFalse(state.hasCustomAdaptiveSplitPreset)
        assertFalse(state.canResetAdaptiveSplitPreset)
        assertTrue(state.adaptiveSplitVisualEditorSupported)
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
    fun `udp fake burst activates generic udp fake profile`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncUdp(true)
                .setUdpFakeCount(3)
                .build()

        val state = settings.toUiState()

        assertTrue(state.hasUdpFakeBurst)
        assertTrue(state.udpFakeProfileActiveInStrategy)
    }

    @Test
    fun `custom fake payload profiles can be reset outside command line mode`() {
        val settings =
            defaults
                .toBuilder()
                .setHttpFakeProfile("cloudflare_get")
                .setTlsFakeProfile("google_chrome")
                .build()

        val state = settings.toUiState()

        assertTrue(state.hasCustomFakePayloadProfiles)
        assertTrue(state.canResetFakePayloadLibrary)
    }

    @Test
    fun `adaptive fake ttl becomes visible and resettable for fake transport`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("fake")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(DefaultAdaptiveFakeTtlDelta)
                .setAdaptiveFakeTtlMin(3)
                .setAdaptiveFakeTtlMax(12)
                .setAdaptiveFakeTtlFallback(11)
                .build()

        val state = settings.toUiState()

        assertTrue(state.fakeTtlControlsRelevant)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertEquals(AdaptiveFakeTtlModeAdaptive, state.adaptiveFakeTtlMode)
        assertTrue(state.hasAdaptiveFakeTtl)
        assertFalse(state.hasCustomAdaptiveFakeTtl)
        assertTrue(state.canResetAdaptiveFakeTtlProfile)
    }

    @Test
    fun `adaptive fake ttl stays hidden for plain oob`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("oob")
                .build()

        val state = settings.toUiState()

        assertTrue(state.isOob)
        assertFalse(state.usesFakeTransport)
        assertFalse(state.hasDisoob)
        assertFalse(state.fakeTtlControlsRelevant)
        assertFalse(state.showAdaptiveFakeTtlProfile)
    }

    @Test
    fun `adaptive fake ttl stays relevant for hostfake transport`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("hostfake")
                        .setMarker("endhost+8")
                        .setMidhostMarker("midsld")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertFalse(state.isFake)
        assertTrue(state.usesFakeTransport)
        assertTrue(state.hasHostFake)
        assertFalse(state.hasDisoob)
        assertTrue(state.fakeTtlControlsRelevant)
        assertTrue(state.showAdaptiveFakeTtlProfile)
    }

    @Test
    fun `adaptive fake ttl stays relevant for disoob chain transport`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("disoob")
                        .setMarker("host")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertFalse(state.isFake)
        assertFalse(state.usesFakeTransport)
        assertTrue(state.hasDisoob)
        assertTrue(state.isOob)
        assertTrue(state.fakeTtlControlsRelevant)
        assertTrue(state.showAdaptiveFakeTtlProfile)
    }

    @Test
    fun `adaptive fake ttl stays relevant for disoob`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("disoob")
                .build()

        val state = settings.toUiState()

        assertTrue(state.isOob)
        assertTrue(state.hasDisoob)
        assertTrue(state.fakeTtlControlsRelevant)
        assertTrue(state.showAdaptiveFakeTtlProfile)
    }

    @Test
    fun `custom adaptive fake ttl mode is preserved from stored delta`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("fake")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(2)
                .setAdaptiveFakeTtlMin(4)
                .setAdaptiveFakeTtlMax(16)
                .setAdaptiveFakeTtlFallback(10)
                .build()

        val state = settings.toUiState()

        assertEquals(AdaptiveFakeTtlModeCustom, state.adaptiveFakeTtlMode)
        assertTrue(state.hasAdaptiveFakeTtl)
        assertTrue(state.hasCustomAdaptiveFakeTtl)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertTrue(state.canResetAdaptiveFakeTtlProfile)
    }

    @Test
    fun `saved adaptive fake ttl stays visible when fake transport is off`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("none")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(DefaultAdaptiveFakeTtlDelta)
                .setAdaptiveFakeTtlFallback(9)
                .build()

        val state = settings.toUiState()

        assertFalse(state.fakeTtlControlsRelevant)
        assertTrue(state.hasAdaptiveFakeTtl)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertTrue(state.canResetAdaptiveFakeTtlProfile)
    }

    @Test
    fun `command line mode keeps adaptive fake ttl visible but disables reset`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .setDesyncMethod("fake")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(3)
                .build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertEquals(AdaptiveFakeTtlModeCustom, state.adaptiveFakeTtlMode)
        assertFalse(state.canResetAdaptiveFakeTtlProfile)
    }

    @Test
    fun `command line mode keeps fake payload library visible but reset disabled`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(false)
                .setHttpFakeProfile(HttpFakeProfileCloudflareGet)
                .setTlsFakeProfile(TlsFakeProfileGoogleChrome)
                .setUdpFakeProfile(UdpFakeProfileDnsQuery)
                .build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertTrue(state.hasCustomFakePayloadProfiles)
        assertTrue(state.showFakePayloadLibrary)
        assertFalse(state.canResetFakePayloadLibrary)
        assertFalse(state.httpFakeProfileActiveInStrategy)
        assertFalse(state.tlsFakeProfileActiveInStrategy)
        assertFalse(state.udpFakeProfileActiveInStrategy)
    }

    @Test
    fun `group activation filter produces custom activation window summary`() {
        val settings =
            defaults
                .toBuilder()
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setRound(NumericRange.newBuilder().setStart(2).setEnd(4))
                        .setPayloadSize(NumericRange.newBuilder().setStart(64).setEnd(512))
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.hasCustomActivationWindow)
        assertEquals("round=2-4 size=64-512", state.activationWindowSummary)
        assertTrue(state.canResetActivationWindow)
        assertTrue(state.showActivationWindowProfile)
    }

    @Test
    fun `step activation filters keep activation profile visible`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fake")
                        .setMarker("host")
                        .setActivationFilter(
                            ActivationFilter
                                .newBuilder()
                                .setRound(NumericRange.newBuilder().setStart(1).setEnd(2))
                                .build(),
                        ).build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.showActivationWindowProfile)
        assertTrue(state.hasStepActivationFilters)
        assertEquals(1, state.stepActivationFilterCount)
        assertFalse(state.canResetActivationWindow)
    }

    @Test
    fun `command line mode keeps activation profile visible but reset disabled`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .setDesyncMethod("none")
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setRound(NumericRange.newBuilder().setStart(2).setEnd(4))
                        .setStreamBytes(NumericRange.newBuilder().setStart(0).setEnd(2047))
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertTrue(state.hasCustomActivationWindow)
        assertTrue(state.showActivationWindowProfile)
        assertFalse(state.canResetActivationWindow)
        assertFalse(state.activationWindowControlsRelevant)
    }

    @Test
    fun `custom activation window remains visible when group desync is off`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncMethod("none")
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setPayloadSize(NumericRange.newBuilder().setStart(64).setEnd(512))
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertFalse(state.desyncEnabled)
        assertTrue(state.hasCustomActivationWindow)
        assertTrue(state.showActivationWindowProfile)
    }

    @Test
    fun `activation profile hides when desync is off and no filters are saved`() {
        val settings = defaults.toBuilder().setDesyncMethod("none").build()

        val state = settings.toUiState()

        assertFalse(state.desyncEnabled)
        assertFalse(state.showActivationWindowProfile)
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
    fun `tlsrandrec step enables tls record split compatibility state`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrandrec")
                        .setMarker("sniext+4")
                        .setFragmentCount(5)
                        .setMinFragmentSize(24)
                        .setMaxFragmentSize(48)
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.tlsRecEnabled)
        assertEquals("sniext+4", state.tlsrecMarker)
        assertEquals(TcpChainStepKind.TlsRandRec.wireName, state.tlsPreludeMode)
        assertEquals(1, state.tlsPreludeStepCount)
        assertEquals(5, state.tlsRandRecFragmentCount)
        assertEquals(24, state.tlsRandRecMinFragmentSize)
        assertEquals(48, state.tlsRandRecMaxFragmentSize)
        assertTrue(state.tlsPreludeUsesRandomRecords)
    }

    @Test
    fun `stacked tls prelude steps are surfaced in ui state`() {
        val settings =
            defaults
                .toBuilder()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrec")
                        .setMarker("extlen")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrandrec")
                        .setMarker("sniext+4")
                        .setFragmentCount(4)
                        .setMinFragmentSize(16)
                        .setMaxFragmentSize(96)
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertEquals(TcpChainStepKind.TlsRec.wireName, state.tlsPreludeMode)
        assertEquals(2, state.tlsPreludeStepCount)
        assertTrue(state.hasStackedTlsPreludeSteps)
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
    fun `compat quic fake profile hides host override state`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .setUdpFakeCount(2)
                .setQuicFakeProfile(QuicFakeProfileCompatDefault)
                .setQuicFakeHost("video.example.test")
                .build()

        val state = settings.toUiState()

        assertTrue(state.showQuicFakeProfile)
        assertFalse(state.showQuicFakeHostOverride)
        assertFalse(state.quicFakeUsesCustomHost)
        assertEquals("", state.quicFakeEffectiveHost)
    }

    @Test
    fun `quic fake profile remains visible in command line mode`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .setDesyncUdp(false)
                .build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertFalse(state.quicFakeControlsRelevant)
        assertTrue(state.showQuicFakeProfile)
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

    @Test
    fun `custom dot resolver populates settings ui dns fields and summary`() {
        val state =
            defaults
                .toBuilder()
                .setDnsIp("9.9.9.9")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDot)
                .setEncryptedDnsHost("dot.example.test")
                .setEncryptedDnsPort(853)
                .setEncryptedDnsTlsServerName("dot.example.test")
                .clearEncryptedDnsBootstrapIps()
                .addAllEncryptedDnsBootstrapIps(listOf("9.9.9.9", "149.112.112.112"))
                .build()
                .toUiState()

        assertEquals(DnsModeEncrypted, state.dnsMode)
        assertEquals(DnsProviderCustom, state.dnsProviderId)
        assertEquals("9.9.9.9", state.dnsIp)
        assertEquals(EncryptedDnsProtocolDot, state.encryptedDnsProtocol)
        assertEquals("dot.example.test", state.encryptedDnsHost)
        assertEquals(853, state.encryptedDnsPort)
        assertEquals("dot.example.test", state.encryptedDnsTlsServerName)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), state.encryptedDnsBootstrapIps)
        assertEquals("Encrypted DNS · Custom resolver (DoT)", state.dnsSummary)
    }

    @Test
    fun `custom dnscrypt resolver populates settings ui dns fields and summary`() {
        val state =
            defaults
                .toBuilder()
                .setDnsIp("8.8.8.8")
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCustom)
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDnsCrypt)
                .setEncryptedDnsHost("dnscrypt.example.test")
                .setEncryptedDnsPort(5443)
                .setEncryptedDnsTlsServerName("")
                .clearEncryptedDnsBootstrapIps()
                .addAllEncryptedDnsBootstrapIps(listOf("8.8.8.8"))
                .setEncryptedDnsDnscryptProviderName("2.dnscrypt-cert.example.test")
                .setEncryptedDnsDnscryptPublicKey(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ).build()
                .toUiState()

        assertEquals(DnsModeEncrypted, state.dnsMode)
        assertEquals(DnsProviderCustom, state.dnsProviderId)
        assertEquals("8.8.8.8", state.dnsIp)
        assertEquals(EncryptedDnsProtocolDnsCrypt, state.encryptedDnsProtocol)
        assertEquals("dnscrypt.example.test", state.encryptedDnsHost)
        assertEquals(5443, state.encryptedDnsPort)
        assertEquals("2.dnscrypt-cert.example.test", state.encryptedDnsDnscryptProviderName)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            state.encryptedDnsDnscryptPublicKey,
        )
        assertEquals("Encrypted DNS · Custom resolver (DNSCrypt)", state.dnsSummary)
    }
}
