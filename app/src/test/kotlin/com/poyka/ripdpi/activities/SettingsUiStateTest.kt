package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerEndHost
import com.poyka.ripdpi.data.AdaptiveMarkerHost
import com.poyka.ripdpi.data.AdaptiveMarkerMethod
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.CanonicalDefaultSplitMarker
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.DefaultDisoobSplitMarker
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.DefaultSeqOverlapSize
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.FakeTlsSniModeRandomized
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.QuicFakeProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.SeqOverlapFakeModeProfile
import com.poyka.ripdpi.data.SeqOverlapFakeModeRand
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.TlsFakeProfileGoogleChrome
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.UdpFakeProfileDnsQuery
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.ActivationFilter
import com.poyka.ripdpi.proto.AppSettings
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
        assertEquals("split", state.desync.desyncMethod)
        assertFalse(state.autolearn.hostAutolearnEnabled)
        assertEquals(DefaultHostAutolearnPenaltyTtlHours, state.autolearn.hostAutolearnPenaltyTtlHours)
        assertEquals(DefaultHostAutolearnMaxHosts, state.autolearn.hostAutolearnMaxHosts)
        assertTrue(state.desyncEnabled)
        assertFalse(state.isFake)
        assertFalse(state.usesFakeTransport)
        assertFalse(state.hasHostFake)
        assertEquals(0, state.desync.hostFakeStepCount)
        assertTrue(state.hostFakeControlsRelevant)
        assertTrue(state.showHostFakeProfile)
        assertFalse(state.desync.hasFakeApproximation)
        assertEquals(0, state.desync.fakeApproximationStepCount)
        assertTrue(state.fakeApproximationControlsRelevant)
        assertTrue(state.showFakeApproximationProfile)
        assertFalse(state.isOob)
        assertEquals(FakeTlsSniModeFixed, state.fake.fakeTlsSniMode)
        assertEquals(0, state.fake.fakeTlsSize)
        assertEquals(FakePayloadProfileCompatDefault, state.fake.httpFakeProfile)
        assertEquals(FakePayloadProfileCompatDefault, state.fake.tlsFakeProfile)
        assertEquals(FakePayloadProfileCompatDefault, state.fake.udpFakeProfile)
        assertFalse(state.fake.hasCustomFakePayloadProfiles)
        assertFalse(state.canResetFakePayloadLibrary)
        assertFalse(state.httpFakeProfileActiveInStrategy)
        assertFalse(state.tlsFakeProfileActiveInStrategy)
        assertFalse(state.udpFakeProfileActiveInStrategy)
        assertTrue(state.fakePayloadLibraryControlsRelevant)
        assertTrue(state.showFakePayloadLibrary)
        assertFalse(state.fakeTlsControlsRelevant)
        assertFalse(state.fake.hasCustomFakeTlsProfile)
        assertFalse(state.canResetFakeTlsProfile)
        assertFalse(state.fake.adaptiveFakeTtlEnabled)
        assertEquals(DefaultAdaptiveFakeTtlDelta, state.fake.adaptiveFakeTtlDelta)
        assertEquals(DefaultAdaptiveFakeTtlMin, state.fake.adaptiveFakeTtlMin)
        assertEquals(DefaultAdaptiveFakeTtlMax, state.fake.adaptiveFakeTtlMax)
        assertEquals(DefaultAdaptiveFakeTtlFallback, state.fake.adaptiveFakeTtlFallback)
        assertEquals(AdaptiveFakeTtlModeFixed, state.fake.adaptiveFakeTtlMode)
        assertFalse(state.fake.hasAdaptiveFakeTtl)
        assertFalse(state.fake.hasCustomAdaptiveFakeTtl)
        assertFalse(state.showAdaptiveFakeTtlProfile)
        assertFalse(state.canResetAdaptiveFakeTtlProfile)
        assertTrue(state.desyncHttpEnabled)
        assertTrue(state.desyncHttpsEnabled)
        assertFalse(state.desyncUdpEnabled)
        assertFalse(state.httpParser.httpMethodEol)
        assertFalse(state.httpParser.httpUnixEol)
        assertFalse(state.httpParser.hasSafeHttpParserTweaks)
        assertFalse(state.httpParser.hasAggressiveHttpParserEvasions)
        assertFalse(state.httpParser.hasCustomHttpParserEvasions)
        assertFalse(state.canResetHttpParserEvasions)
        assertTrue(state.httpParserControlsRelevant)
        assertTrue(state.showHttpParserProfile)
        assertEquals(QuicFakeProfileDisabled, state.quic.quicFakeProfile)
        assertEquals("", state.quic.quicFakeHost)
        assertFalse(state.quic.quicFakeProfileActive)
        assertFalse(state.desync.hasUdpFakeBurst)
        assertFalse(state.quicFakeControlsRelevant)
        assertFalse(state.quic.showQuicFakeHostOverride)
        assertFalse(state.quic.quicFakeUsesCustomHost)
        assertEquals("", state.quic.quicFakeEffectiveHost)
        assertFalse(state.showQuicFakeProfile)
        assertFalse(state.tlsRecEnabled)
        assertEquals("disabled", state.tlsPrelude.tlsPreludeMode)
        assertEquals(0, state.tlsPrelude.tlsPreludeStepCount)
        assertEquals(DefaultTlsRandRecFragmentCount, state.tlsPrelude.tlsRandRecFragmentCount)
        assertEquals(DefaultTlsRandRecMinFragmentSize, state.tlsPrelude.tlsRandRecMinFragmentSize)
        assertEquals(DefaultTlsRandRecMaxFragmentSize, state.tlsPrelude.tlsRandRecMaxFragmentSize)
        assertTrue(state.tlsPreludeControlsRelevant)
        assertTrue(state.showTlsPreludeProfile)
        assertFalse(state.tlsPrelude.tlsPreludeUsesRandomRecords)
        assertFalse(state.tlsPrelude.hasStackedTlsPreludeSteps)
        assertTrue(state.showActivationWindowProfile)
        assertFalse(state.canResetActivationWindow)
        assertFalse(state.desync.hasStepActivationFilters)
        assertEquals(0, state.desync.stepActivationFilterCount)
        assertFalse(state.webrtcProtectionEnabled)
        assertFalse(state.biometricEnabled)
        assertEquals("", state.backupPinHash)
        assertFalse(state.hasBackupPin)
        assertEquals(LauncherIconManager.DefaultIconKey, state.appIconVariant)
        assertTrue(state.themedAppIconEnabled)
        assertEquals(CanonicalDefaultSplitMarker, state.desync.splitMarker)
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
        val settings = defaults.toBuilder().withPrimaryDesyncMethod("none").build()
        val state = settings.toUiState()
        assertFalse(state.desyncEnabled)
        assertFalse(state.isFake)
        assertFalse(state.isOob)
    }

    @Test
    fun `desync method fake sets isFake`() {
        val settings = defaults.toBuilder().withPrimaryDesyncMethod("fake").build()
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

        assertTrue(state.httpParser.hostMixedCase)
        assertTrue(state.httpParser.domainMixedCase)
        assertTrue(state.httpParser.hostRemoveSpaces)
        assertTrue(state.httpParser.httpMethodEol)
        assertTrue(state.httpParser.httpUnixEol)
        assertTrue(state.httpParser.hasSafeHttpParserTweaks)
        assertTrue(state.httpParser.hasAggressiveHttpParserEvasions)
        assertTrue(state.httpParser.hasCustomHttpParserEvasions)
        assertEquals(3, state.httpParser.httpParserSafeCount)
        assertEquals(2, state.httpParser.httpParserAggressiveCount)
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
        assertTrue(state.httpParser.hasCustomHttpParserEvasions)
        assertTrue(state.showHttpParserProfile)
        assertFalse(state.canResetHttpParserEvasions)
    }

    @Test
    fun `saved http parser evasions stay visible when http desync is off`() {
        val settings =
            defaults
                .toBuilder()
                .withPrimaryDesyncMethod("none")
                .setDesyncHttp(false)
                .setDesyncHttps(true)
                .setHostRemoveSpaces(true)
                .build()

        val state = settings.toUiState()

        assertFalse(state.httpParserControlsRelevant)
        assertTrue(state.httpParser.hasCustomHttpParserEvasions)
        assertTrue(state.showHttpParserProfile)
    }

    @Test
    fun `hostfake step enables fake transport without fake tls controls`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
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
        assertEquals(1, state.desync.hostFakeStepCount)
        assertEquals("endhost+8", state.desync.primaryHostFakeStep?.marker)
        assertEquals("midsld", state.desync.primaryHostFakeStep?.midhostMarker)
        assertEquals("googlevideo.com", state.desync.primaryHostFakeStep?.fakeHostTemplate)
        assertFalse(state.httpFakeProfileActiveInStrategy)
        assertFalse(state.tlsFakeProfileActiveInStrategy)
        assertFalse(state.fakeTlsControlsRelevant)
        assertEquals("tcp: hostfake(endhost+8 midhost=midsld host=googlevideo.com)", state.desync.chainSummary)
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
    fun `seqovl profile step activates fake payload guidance without fake transport`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("tlsrec")
                        .setMarker("extlen")
                        .build(),
                ).addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("seqovl")
                        .setMarker("midsld")
                        .setOverlapSize(16)
                        .setFakeMode(SeqOverlapFakeModeProfile)
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertFalse(state.isFake)
        assertFalse(state.usesFakeTransport)
        assertTrue(state.desync.hasSeqOverlap)
        assertEquals(1, state.desync.seqOverlapStepCount)
        assertEquals(TcpChainStepKind.SeqOverlap, state.desync.primarySeqOverlapStep?.kind)
        assertEquals("midsld", state.desync.primarySeqOverlapStep?.marker)
        assertEquals(16, state.desync.seqOverlapEffectiveSize)
        assertTrue(state.usesSeqOverlapFakeProfile)
        assertTrue(state.httpFakeProfileActiveInStrategy)
        assertTrue(state.tlsFakeProfileActiveInStrategy)
        assertTrue(state.fakeTlsControlsRelevant)
        assertTrue(state.showSeqOverlapProfile)
        assertFalse(state.seqOverlapUnavailableOnDevice)
        assertEquals("tcp: tlsrec(extlen) -> seqovl(midsld overlap=16 fake=profile)", state.desync.chainSummary)
    }

    @Test
    fun `saved seqovl rand stays visible when http and https are off`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .clearTcpChainSteps()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("seqovl")
                        .setMarker("host+1")
                        .setOverlapSize(DefaultSeqOverlapSize)
                        .setFakeMode(SeqOverlapFakeModeRand)
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.desync.hasSeqOverlap)
        assertFalse(state.usesSeqOverlapFakeProfile)
        assertFalse(state.seqOverlapControlsRelevant)
        assertTrue(state.showSeqOverlapProfile)
        assertFalse(state.seqOverlapUnavailableOnDevice)
        assertFalse(state.httpFakeProfileActiveInStrategy)
        assertFalse(state.tlsFakeProfileActiveInStrategy)
        assertFalse(state.fakeTlsControlsRelevant)
    }

    @Test
    fun `seqovl guidance hides when no relevant protocols are enabled`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .build()

        val state = settings.toUiState()

        assertFalse(state.seqOverlapControlsRelevant)
        assertFalse(state.showSeqOverlapProfile)
        assertTrue(state.seqOverlapUnavailableOnDevice)
    }

    @Test
    fun `fake approximation steps activate fake payload and fake tls controls`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttps(true)
                .clearTcpChainSteps()
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
        assertTrue(state.desync.hasFakeApproximation)
        assertEquals(1, state.desync.fakeApproximationStepCount)
        assertTrue(state.desync.hasFakeSplitApproximation)
        assertFalse(state.desync.hasFakeDisorderApproximation)
        assertEquals(TcpChainStepKind.FakeSplit, state.desync.primaryFakeApproximationStep?.kind)
        assertEquals("host+1", state.desync.primaryFakeApproximationStep?.marker)
        assertTrue(state.showFakeApproximationProfile)
    }

    @Test
    fun `fakeddisorder keeps adaptive fake ttl relevant`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
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
        assertTrue(state.desync.hasFakeApproximation)
        assertFalse(state.desync.hasFakeSplitApproximation)
        assertTrue(state.desync.hasFakeDisorderApproximation)
        assertEquals(TcpChainStepKind.FakeDisorder, state.desync.primaryFakeApproximationStep?.kind)
        assertEquals("tcp: fakeddisorder(endhost)", state.desync.chainSummary)
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
                .clearTcpChainSteps()
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

        assertTrue(state.desync.hasFakeApproximation)
        assertEquals(2, state.desync.fakeApproximationStepCount)
        assertTrue(state.desync.hasFakeSplitApproximation)
        assertTrue(state.desync.hasFakeDisorderApproximation)
        assertEquals(TcpChainStepKind.FakeSplit, state.desync.primaryFakeApproximationStep?.kind)
        assertEquals("host+1", state.desync.primaryFakeApproximationStep?.marker)
    }

    @Test
    fun `saved fake approximation stays visible when http and https are off`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .clearTcpChainSteps()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("fakedsplit")
                        .setMarker("host+1")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.desync.hasFakeApproximation)
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
                .clearTcpChainSteps()
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
        assertTrue(state.desync.hasFakeApproximation)
        assertEquals(2, state.desync.fakeApproximationStepCount)
        assertEquals(TcpChainStepKind.FakeDisorder, state.desync.primaryFakeApproximationStep?.kind)
        assertEquals("host+1", state.desync.primaryFakeApproximationStep?.marker)
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
                .withPrimaryDesyncMethod("split", AdaptiveMarkerBalanced)
                .build()
                .toUiState()
        val customState =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
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
                .clearTcpChainSteps()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("hostfake")
                        .setMarker("endhost+8")
                        .build(),
                ).build()
                .toUiState()

        assertEquals(AdaptiveMarkerBalanced, balancedState.desync.adaptiveSplitPreset)
        assertTrue(balancedState.desync.hasAdaptiveSplitPreset)
        assertFalse(balancedState.desync.hasCustomAdaptiveSplitPreset)
        assertTrue(balancedState.canResetAdaptiveSplitPreset)
        assertEquals(AdaptiveSplitPresetCustom, customState.desync.adaptiveSplitPreset)
        assertTrue(customState.desync.hasAdaptiveSplitPreset)
        assertTrue(customState.desync.hasCustomAdaptiveSplitPreset)
        assertTrue(customState.canResetAdaptiveSplitPreset)
        assertFalse(hostfakeState.desync.adaptiveSplitVisualEditorSupported)
        assertFalse(hostfakeState.canResetAdaptiveSplitPreset)
    }

    @Test
    fun `adaptive split visual editor follows first non tls tcp step`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
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

        assertEquals(AdaptiveMarkerEndHost, state.desync.splitMarker)
        assertEquals(AdaptiveMarkerEndHost, state.desync.adaptiveSplitPreset)
        assertTrue(state.desync.hasAdaptiveSplitPreset)
        assertFalse(state.desync.hasCustomAdaptiveSplitPreset)
        assertTrue(state.desync.adaptiveSplitVisualEditorSupported)
    }

    @Test
    fun `manual adaptive split state does not expose reset`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
                .addTcpChainSteps(
                    StrategyTcpStep
                        .newBuilder()
                        .setKind("split")
                        .setMarker("host+2")
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertEquals("host+2", state.desync.splitMarker)
        assertEquals(AdaptiveSplitPresetManual, state.desync.adaptiveSplitPreset)
        assertFalse(state.desync.hasAdaptiveSplitPreset)
        assertFalse(state.desync.hasCustomAdaptiveSplitPreset)
        assertFalse(state.canResetAdaptiveSplitPreset)
        assertTrue(state.desync.adaptiveSplitVisualEditorSupported)
    }

    @Test
    fun `command line mode keeps adaptive split preset visible but disables reset`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .withPrimaryDesyncMethod("split", AdaptiveMarkerHost)
                .build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertEquals(AdaptiveMarkerHost, state.desync.adaptiveSplitPreset)
        assertTrue(state.desync.hasAdaptiveSplitPreset)
        assertFalse(state.desync.hasCustomAdaptiveSplitPreset)
        assertFalse(state.canResetAdaptiveSplitPreset)
        assertTrue(state.desync.adaptiveSplitVisualEditorSupported)
    }

    @Test
    fun `desync method oob sets isOob`() {
        val settings = defaults.toBuilder().withPrimaryDesyncMethod("oob").build()
        assertTrue(settings.toUiState().isOob)
    }

    @Test
    fun `desync method disoob sets isOob`() {
        val settings = defaults.toBuilder().withPrimaryDesyncMethod("disoob").build()
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
                .withUdpFakeCount(3)
                .build()

        val state = settings.toUiState()

        assertTrue(state.desync.hasUdpFakeBurst)
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

        assertTrue(state.fake.hasCustomFakePayloadProfiles)
        assertTrue(state.canResetFakePayloadLibrary)
    }

    @Test
    fun `adaptive fake ttl becomes visible and resettable for fake transport`() {
        val settings =
            defaults
                .toBuilder()
                .withPrimaryDesyncMethod("fake")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(DefaultAdaptiveFakeTtlDelta)
                .setAdaptiveFakeTtlMin(3)
                .setAdaptiveFakeTtlMax(12)
                .setAdaptiveFakeTtlFallback(11)
                .build()

        val state = settings.toUiState()

        assertTrue(state.fakeTtlControlsRelevant)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertEquals(AdaptiveFakeTtlModeAdaptive, state.fake.adaptiveFakeTtlMode)
        assertTrue(state.fake.hasAdaptiveFakeTtl)
        assertFalse(state.fake.hasCustomAdaptiveFakeTtl)
        assertTrue(state.canResetAdaptiveFakeTtlProfile)
    }

    @Test
    fun `adaptive fake ttl stays hidden for plain oob`() {
        val settings =
            defaults
                .toBuilder()
                .withPrimaryDesyncMethod("oob")
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
                .clearTcpChainSteps()
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
                .clearTcpChainSteps()
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
                .withPrimaryDesyncMethod("disoob")
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
                .withPrimaryDesyncMethod("fake")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(2)
                .setAdaptiveFakeTtlMin(4)
                .setAdaptiveFakeTtlMax(16)
                .setAdaptiveFakeTtlFallback(10)
                .build()

        val state = settings.toUiState()

        assertEquals(AdaptiveFakeTtlModeCustom, state.fake.adaptiveFakeTtlMode)
        assertTrue(state.fake.hasAdaptiveFakeTtl)
        assertTrue(state.fake.hasCustomAdaptiveFakeTtl)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertTrue(state.canResetAdaptiveFakeTtlProfile)
    }

    @Test
    fun `saved adaptive fake ttl stays visible when fake transport is off`() {
        val settings =
            defaults
                .toBuilder()
                .withPrimaryDesyncMethod("none")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(DefaultAdaptiveFakeTtlDelta)
                .setAdaptiveFakeTtlFallback(9)
                .build()

        val state = settings.toUiState()

        assertFalse(state.fakeTtlControlsRelevant)
        assertTrue(state.fake.hasAdaptiveFakeTtl)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertTrue(state.canResetAdaptiveFakeTtlProfile)
    }

    @Test
    fun `command line mode keeps adaptive fake ttl visible but disables reset`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .withPrimaryDesyncMethod("fake")
                .setAdaptiveFakeTtlEnabled(true)
                .setAdaptiveFakeTtlDelta(3)
                .build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertTrue(state.showAdaptiveFakeTtlProfile)
        assertEquals(AdaptiveFakeTtlModeCustom, state.fake.adaptiveFakeTtlMode)
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
        assertTrue(state.fake.hasCustomFakePayloadProfiles)
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

        assertTrue(state.desync.hasCustomActivationWindow)
        assertEquals("round=2-4 size=64-512", state.desync.activationWindowSummary)
        assertTrue(state.canResetActivationWindow)
        assertTrue(state.showActivationWindowProfile)
    }

    @Test
    fun `step activation filters keep activation profile visible`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
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
        assertTrue(state.desync.hasStepActivationFilters)
        assertEquals(1, state.desync.stepActivationFilterCount)
        assertFalse(state.canResetActivationWindow)
    }

    @Test
    fun `command line mode keeps activation profile visible but reset disabled`() {
        val settings =
            defaults
                .toBuilder()
                .setEnableCmdSettings(true)
                .withPrimaryDesyncMethod("none")
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setRound(NumericRange.newBuilder().setStart(2).setEnd(4))
                        .setStreamBytes(NumericRange.newBuilder().setStart(0).setEnd(2047))
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertTrue(state.enableCmdSettings)
        assertTrue(state.desync.hasCustomActivationWindow)
        assertTrue(state.showActivationWindowProfile)
        assertFalse(state.canResetActivationWindow)
        assertFalse(state.activationWindowControlsRelevant)
    }

    @Test
    fun `custom activation window remains visible when group desync is off`() {
        val settings =
            defaults
                .toBuilder()
                .withPrimaryDesyncMethod("none")
                .setGroupActivationFilter(
                    ActivationFilter
                        .newBuilder()
                        .setPayloadSize(NumericRange.newBuilder().setStart(64).setEnd(512))
                        .build(),
                ).build()

        val state = settings.toUiState()

        assertFalse(state.desyncEnabled)
        assertTrue(state.desync.hasCustomActivationWindow)
        assertTrue(state.showActivationWindowProfile)
    }

    @Test
    fun `activation profile hides when desync is off and no filters are saved`() {
        val settings = defaults.toBuilder().withPrimaryDesyncMethod("none").build()

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
                .withTlsRecordSplit(true)
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
                .withTlsRecordSplit(true)
                .build()
        assertFalse(settings.toUiState().tlsRecEnabled)
    }

    @Test
    fun `tlsrandrec step enables tls record split compatibility state`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
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
        assertEquals("sniext+4", state.tlsPrelude.tlsrecMarker)
        assertEquals(TcpChainStepKind.TlsRandRec.wireName, state.tlsPrelude.tlsPreludeMode)
        assertEquals(1, state.tlsPrelude.tlsPreludeStepCount)
        assertEquals(5, state.tlsPrelude.tlsRandRecFragmentCount)
        assertEquals(24, state.tlsPrelude.tlsRandRecMinFragmentSize)
        assertEquals(48, state.tlsPrelude.tlsRandRecMaxFragmentSize)
        assertTrue(state.tlsPrelude.tlsPreludeUsesRandomRecords)
    }

    @Test
    fun `stacked tls prelude steps are surfaced in ui state`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
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

        assertEquals(TcpChainStepKind.TlsRec.wireName, state.tlsPrelude.tlsPreludeMode)
        assertEquals(2, state.tlsPrelude.tlsPreludeStepCount)
        assertTrue(state.tlsPrelude.hasStackedTlsPreludeSteps)
    }

    @Test
    fun `cmd settings enabled`() {
        val settings = defaults.toBuilder().setEnableCmdSettings(true).build()
        assertTrue(settings.toUiState().useCmdSettings)
    }

    @Test
    fun `empty tcp chain keeps canonical no-desync state`() {
        val settings =
            defaults
                .toBuilder()
                .clearTcpChainSteps()
                .build()
        val state = settings.toUiState()
        assertEquals("none", state.desync.desyncMethod)
        assertEquals(CanonicalDefaultSplitMarker, state.desync.splitMarker)
    }

    @Test
    fun `missing primary marker falls back to normalized split default`() {
        val settings =
            defaults
                .toBuilder()
                .withPrimaryDesyncMethod("split", "")
                .build()

        assertEquals(DefaultSplitMarker, settings.toUiState().desync.splitMarker)
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

        assertTrue(state.autolearn.hostAutolearnEnabled)
        assertEquals(DefaultHostAutolearnPenaltyTtlHours, state.autolearn.hostAutolearnPenaltyTtlHours)
        assertEquals(DefaultHostAutolearnMaxHosts, state.autolearn.hostAutolearnMaxHosts)
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
        assertTrue(state.autolearn.hostAutolearnRuntimeEnabled)
        assertTrue(state.autolearn.hostAutolearnStorePresent)
        assertEquals(7, state.autolearn.hostAutolearnLearnedHostCount)
        assertEquals(2, state.autolearn.hostAutolearnPenalizedHostCount)
        assertEquals("example.org", state.autolearn.hostAutolearnLastHost)
        assertEquals(3, state.autolearn.hostAutolearnLastGroup)
        assertEquals("host_promoted", state.autolearn.hostAutolearnLastAction)
    }

    @Test
    fun `fake tls profile is exposed in ui state`() {
        val settings =
            defaults
                .toBuilder()
                .withPrimaryDesyncMethod("fake")
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
        assertTrue(state.fake.hasCustomFakeTlsProfile)
        assertEquals(FakeTlsSniModeRandomized, state.fake.fakeTlsSniMode)
        assertEquals(-24, state.fake.fakeTlsSize)
        assertTrue(state.fake.fakeTlsUseOriginal)
        assertTrue(state.fake.fakeTlsRandomize)
        assertTrue(state.fake.fakeTlsDupSessionId)
        assertTrue(state.fake.fakeTlsPadEncap)
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
                .withUdpFakeCount(3)
                .setQuicFakeProfile(QuicFakeProfileRealisticInitial)
                .setQuicFakeHost("video.example.test")
                .build()

        val state = settings.toUiState()

        assertTrue(state.desyncUdpEnabled)
        assertTrue(state.quicFakeControlsRelevant)
        assertTrue(state.desync.hasUdpFakeBurst)
        assertTrue(state.quic.quicFakeProfileActive)
        assertTrue(state.showQuicFakeProfile)
        assertTrue(state.quic.showQuicFakeHostOverride)
        assertTrue(state.quic.quicFakeUsesCustomHost)
        assertEquals("video.example.test", state.quic.quicFakeEffectiveHost)
        assertEquals(QuicFakeProfileRealisticInitial, state.quic.quicFakeProfile)
        assertEquals("video.example.test", state.quic.quicFakeHost)
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

        assertTrue(state.quic.showQuicFakeHostOverride)
        assertFalse(state.quic.quicFakeUsesCustomHost)
        assertEquals(DefaultQuicFakeHost, state.quic.quicFakeEffectiveHost)
    }

    @Test
    fun `compat quic fake profile hides host override state`() {
        val settings =
            defaults
                .toBuilder()
                .setDesyncHttp(false)
                .setDesyncHttps(false)
                .setDesyncUdp(true)
                .withUdpFakeCount(2)
                .setQuicFakeProfile(QuicFakeProfileCompatDefault)
                .setQuicFakeHost("video.example.test")
                .build()

        val state = settings.toUiState()

        assertTrue(state.showQuicFakeProfile)
        assertFalse(state.quic.showQuicFakeHostOverride)
        assertFalse(state.quic.quicFakeUsesCustomHost)
        assertEquals("", state.quic.quicFakeEffectiveHost)
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
                .withPrimaryDesyncMethod("fake")
                .setFakeSni("alt.example.org")
                .build()

        val state = settings.toUiState()

        assertTrue(state.fakeTlsControlsRelevant)
        assertTrue(state.fake.hasCustomFakeTlsProfile)
        assertTrue(state.canResetFakeTlsProfile)
        assertEquals("alt.example.org", state.fake.fakeSni)
        assertTrue(DefaultFakeSni != state.fake.fakeSni)
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

        assertEquals(DnsModeEncrypted, state.dns.dnsMode)
        assertEquals(DnsProviderCustom, state.dns.dnsProviderId)
        assertEquals("9.9.9.9", state.dns.dnsIp)
        assertEquals(EncryptedDnsProtocolDot, state.dns.encryptedDnsProtocol)
        assertEquals("dot.example.test", state.dns.encryptedDnsHost)
        assertEquals(853, state.dns.encryptedDnsPort)
        assertEquals("dot.example.test", state.dns.encryptedDnsTlsServerName)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), state.dns.encryptedDnsBootstrapIps)
        assertEquals("Encrypted DNS · Custom resolver (DoT)", state.dns.dnsSummary)
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

        assertEquals(DnsModeEncrypted, state.dns.dnsMode)
        assertEquals(DnsProviderCustom, state.dns.dnsProviderId)
        assertEquals("8.8.8.8", state.dns.dnsIp)
        assertEquals(EncryptedDnsProtocolDnsCrypt, state.dns.encryptedDnsProtocol)
        assertEquals("dnscrypt.example.test", state.dns.encryptedDnsHost)
        assertEquals(5443, state.dns.encryptedDnsPort)
        assertEquals("2.dnscrypt-cert.example.test", state.dns.encryptedDnsDnscryptProviderName)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            state.dns.encryptedDnsDnscryptPublicKey,
        )
        assertEquals("Encrypted DNS · Custom resolver (DNSCrypt)", state.dns.dnsSummary)
    }

    private fun AppSettings.Builder.withPrimaryDesyncMethod(
        method: String,
        marker: String = defaultPrimaryMarker(method),
    ): AppSettings.Builder {
        val currentSettings = build()
        val tlsPreludeSteps = currentSettings.effectiveTcpChainSteps().filter { it.kind.isTlsPrelude }
        val primaryStep =
            when (method) {
                "none" -> null
                "split" -> TcpChainStepModel(TcpChainStepKind.Split, marker)
                "fake" -> TcpChainStepModel(TcpChainStepKind.Fake, marker)
                "oob" -> TcpChainStepModel(TcpChainStepKind.Oob, marker)
                "disoob" -> TcpChainStepModel(TcpChainStepKind.Disoob, marker)
                else -> error("Unsupported test desync method: $method")
            }
        return setStrategyChains(
            tcpSteps = listOfNotNull(primaryStep).let { primarySteps -> tlsPreludeSteps + primarySteps },
            udpSteps = currentSettings.effectiveUdpChainSteps(),
        )
    }

    private fun AppSettings.Builder.withUdpFakeCount(count: Int): AppSettings.Builder {
        val currentSettings = build()
        val udpSteps = if (count > 0) listOf(UdpChainStepModel(count = count)) else emptyList()
        return setStrategyChains(
            tcpSteps = currentSettings.effectiveTcpChainSteps(),
            udpSteps = udpSteps,
        )
    }

    private fun AppSettings.Builder.withTlsRecordSplit(
        enabled: Boolean,
        marker: String = DefaultTlsRecordMarker,
    ): AppSettings.Builder {
        val currentSettings = build()
        val primarySteps = currentSettings.effectiveTcpChainSteps().filterNot { it.kind.isTlsPrelude }
        val tlsPreludeSteps = if (enabled) listOf(TcpChainStepModel(TcpChainStepKind.TlsRec, marker)) else emptyList()
        return setStrategyChains(
            tcpSteps = tlsPreludeSteps + primarySteps,
            udpSteps = currentSettings.effectiveUdpChainSteps(),
        )
    }

    private fun defaultPrimaryMarker(method: String): String =
        when (method) {
            "fake" -> DefaultFakeOffsetMarker
            "disoob" -> DefaultDisoobSplitMarker
            else -> CanonicalDefaultSplitMarker
        }
}
