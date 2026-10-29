package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.proto.AppSettings

/**
 * One AppSettings field the proxy-preferences strategy writer sets, with a reader and a copier.
 * The list mirrors the desync, fake-packet, parser, adaptive, QUIC, host and protocol-toggle
 * sections of `applyToSettings`. Listen, relay, WARP, AWG and runtime fields are not in it, so a
 * replay never writes them.
 */
internal class StrategySettingsSlot(
    val read: (AppSettings) -> Any?,
    val copy: (AppSettings.Builder, AppSettings) -> Unit,
)

private fun slot(
    read: (AppSettings) -> Any?,
    copy: (AppSettings.Builder, AppSettings) -> Unit,
) = StrategySettingsSlot(read, copy)

internal val StrategySettingsSlots: List<StrategySettingsSlot> =
    listOf(
        slot({ it.tcpChainStepsList }) { b, s -> b.clearTcpChainSteps().addAllTcpChainSteps(s.tcpChainStepsList) },
        slot({ it.udpChainStepsList }) { b, s -> b.clearUdpChainSteps().addAllUdpChainSteps(s.udpChainStepsList) },
        slot({ if (it.hasGroupActivationFilter()) it.groupActivationFilter else null }) { b, s ->
            if (s.hasGroupActivationFilter()) {
                b.setGroupActivationFilter(s.groupActivationFilter)
            } else {
                b.clearGroupActivationFilter()
            }
        },
        slot({ it.desyncAnyProtocol }) { b, s -> b.setDesyncAnyProtocol(s.desyncAnyProtocol) },
        slot({ it.fakeTtl }) { b, s -> b.setFakeTtl(s.fakeTtl) },
        slot({ it.adaptiveFakeTtlEnabled }) { b, s -> b.setAdaptiveFakeTtlEnabled(s.adaptiveFakeTtlEnabled) },
        slot({ it.adaptiveFakeTtlDelta }) { b, s -> b.setAdaptiveFakeTtlDelta(s.adaptiveFakeTtlDelta) },
        slot({ it.adaptiveFakeTtlMin }) { b, s -> b.setAdaptiveFakeTtlMin(s.adaptiveFakeTtlMin) },
        slot({ it.adaptiveFakeTtlMax }) { b, s -> b.setAdaptiveFakeTtlMax(s.adaptiveFakeTtlMax) },
        slot({ it.adaptiveFakeTtlFallback }) { b, s -> b.setAdaptiveFakeTtlFallback(s.adaptiveFakeTtlFallback) },
        slot({ it.fakeSni }) { b, s -> b.setFakeSni(s.fakeSni) },
        slot({ it.fakeTlsSource }) { b, s -> b.setFakeTlsSource(s.fakeTlsSource) },
        slot({ it.fakeTlsSecondaryProfile }) { b, s -> b.setFakeTlsSecondaryProfile(s.fakeTlsSecondaryProfile) },
        slot({ it.fakeTcpTimestampEnabled }) { b, s -> b.setFakeTcpTimestampEnabled(s.fakeTcpTimestampEnabled) },
        slot(
            { it.fakeTcpTimestampDeltaTicks },
            { b, s -> b.setFakeTcpTimestampDeltaTicks(s.fakeTcpTimestampDeltaTicks) },
        ),
        slot({ it.httpFakeProfile }) { b, s -> b.setHttpFakeProfile(s.httpFakeProfile) },
        slot({ it.fakeTlsUseOriginal }) { b, s -> b.setFakeTlsUseOriginal(s.fakeTlsUseOriginal) },
        slot({ it.fakeTlsRandomize }) { b, s -> b.setFakeTlsRandomize(s.fakeTlsRandomize) },
        slot({ it.fakeTlsDupSessionId }) { b, s -> b.setFakeTlsDupSessionId(s.fakeTlsDupSessionId) },
        slot({ it.fakeTlsPadEncap }) { b, s -> b.setFakeTlsPadEncap(s.fakeTlsPadEncap) },
        slot({ it.fakeTlsSize }) { b, s -> b.setFakeTlsSize(s.fakeTlsSize) },
        slot({ it.fakeTlsSniMode }) { b, s -> b.setFakeTlsSniMode(s.fakeTlsSniMode) },
        slot({ it.tlsFakeProfile }) { b, s -> b.setTlsFakeProfile(s.tlsFakeProfile) },
        slot({ it.udpFakeProfile }) { b, s -> b.setUdpFakeProfile(s.udpFakeProfile) },
        slot({ it.fakeOffsetMarker }) { b, s -> b.setFakeOffsetMarker(s.fakeOffsetMarker) },
        slot({ it.oobData }) { b, s -> b.setOobData(s.oobData) },
        slot({ it.dropSack }) { b, s -> b.setDropSack(s.dropSack) },
        slot({ it.fakeMd5Sig }) { b, s -> b.setFakeMd5Sig(s.fakeMd5Sig) },
        slot({ it.tlsMinorEnabled }) { b, s -> b.setTlsMinorEnabled(s.tlsMinorEnabled) },
        slot({ it.tlsMinorValue }) { b, s -> b.setTlsMinorValue(s.tlsMinorValue) },
        slot({ it.windowClamp }) { b, s -> b.setWindowClamp(s.windowClamp) },
        slot({ it.wsizeWindow }) { b, s -> b.setWsizeWindow(s.wsizeWindow) },
        slot({ it.wsizeScale }) { b, s -> b.setWsizeScale(s.wsizeScale) },
        slot({ it.stripTimestamps }) { b, s -> b.setStripTimestamps(s.stripTimestamps) },
        slot({ it.ipIdMode }) { b, s -> b.setIpIdMode(s.ipIdMode) },
        slot({ it.quicBindLowPort }) { b, s -> b.setQuicBindLowPort(s.quicBindLowPort) },
        slot({ it.quicMigrateAfterHandshake }) { b, s -> b.setQuicMigrateAfterHandshake(s.quicMigrateAfterHandshake) },
        slot({ it.entropyMode }) { b, s -> b.setEntropyMode(s.entropyMode) },
        slot(
            { it.entropyPaddingTargetPermil },
            { b, s -> b.setEntropyPaddingTargetPermil(s.entropyPaddingTargetPermil) },
        ),
        slot({ it.entropyPaddingMax }) { b, s -> b.setEntropyPaddingMax(s.entropyPaddingMax) },
        slot(
            { it.shannonEntropyTargetPermil },
            { b, s -> b.setShannonEntropyTargetPermil(s.shannonEntropyTargetPermil) },
        ),
        slot({ it.tlsFingerprintProfile }) { b, s -> b.setTlsFingerprintProfile(s.tlsFingerprintProfile) },
        slot({ it.hostMixedCase }) { b, s -> b.setHostMixedCase(s.hostMixedCase) },
        slot({ it.domainMixedCase }) { b, s -> b.setDomainMixedCase(s.domainMixedCase) },
        slot({ it.hostRemoveSpaces }) { b, s -> b.setHostRemoveSpaces(s.hostRemoveSpaces) },
        slot({ it.httpMethodEol }) { b, s -> b.setHttpMethodEol(s.httpMethodEol) },
        slot({ it.httpMethodSpace }) { b, s -> b.setHttpMethodSpace(s.httpMethodSpace) },
        slot({ it.httpUnixEol }) { b, s -> b.setHttpUnixEol(s.httpUnixEol) },
        slot({ it.httpHostPad }) { b, s -> b.setHttpHostPad(s.httpHostPad) },
        slot({ it.httpHostExtraSpace }) { b, s -> b.setHttpHostExtraSpace(s.httpHostExtraSpace) },
        slot({ it.httpHostTab }) { b, s -> b.setHttpHostTab(s.httpHostTab) },
        slot({ it.adaptiveFallbackEnabled }) { b, s -> b.setAdaptiveFallbackEnabled(s.adaptiveFallbackEnabled) },
        slot({ it.adaptiveFallbackTorst }) { b, s -> b.setAdaptiveFallbackTorst(s.adaptiveFallbackTorst) },
        slot({ it.adaptiveFallbackTlsErr }) { b, s -> b.setAdaptiveFallbackTlsErr(s.adaptiveFallbackTlsErr) },
        slot(
            { it.adaptiveFallbackHttpRedirect },
            { b, s -> b.setAdaptiveFallbackHttpRedirect(s.adaptiveFallbackHttpRedirect) },
        ),
        slot(
            { it.adaptiveFallbackConnectFailure },
            { b, s -> b.setAdaptiveFallbackConnectFailure(s.adaptiveFallbackConnectFailure) },
        ),
        slot({ it.adaptiveFallbackAutoSort }) { b, s -> b.setAdaptiveFallbackAutoSort(s.adaptiveFallbackAutoSort) },
        slot(
            { it.adaptiveFallbackCacheTtlSeconds },
            { b, s -> b.setAdaptiveFallbackCacheTtlSeconds(s.adaptiveFallbackCacheTtlSeconds) },
        ),
        slot(
            { it.adaptiveFallbackCachePrefixV4 },
            { b, s -> b.setAdaptiveFallbackCachePrefixV4(s.adaptiveFallbackCachePrefixV4) },
        ),
        slot({ it.strategyEvolution }) { b, s -> b.setStrategyEvolution(s.strategyEvolution) },
        slot({ it.evolutionEpsilon }) { b, s -> b.setEvolutionEpsilon(s.evolutionEpsilon) },
        slot({ it.evolutionExperimentTtlMs }) { b, s -> b.setEvolutionExperimentTtlMs(s.evolutionExperimentTtlMs) },
        slot({ it.evolutionDecayHalfLifeMs }) { b, s -> b.setEvolutionDecayHalfLifeMs(s.evolutionDecayHalfLifeMs) },
        slot(
            { it.evolutionCooldownAfterFailures },
            { b, s -> b.setEvolutionCooldownAfterFailures(s.evolutionCooldownAfterFailures) },
        ),
        slot({ it.evolutionCooldownMs }) { b, s -> b.setEvolutionCooldownMs(s.evolutionCooldownMs) },
        slot({ it.quicInitialMode }) { b, s -> b.setQuicInitialMode(s.quicInitialMode) },
        slot({ it.quicSupportV1 }) { b, s -> b.setQuicSupportV1(s.quicSupportV1) },
        slot({ it.quicSupportV2 }) { b, s -> b.setQuicSupportV2(s.quicSupportV2) },
        slot({ it.quicFakeProfile }) { b, s -> b.setQuicFakeProfile(s.quicFakeProfile) },
        slot({ it.quicFakeHost }) { b, s -> b.setQuicFakeHost(s.quicFakeHost) },
        slot({ it.hostsMode }) { b, s -> b.setHostsMode(s.hostsMode) },
        slot({ it.hostsBlacklist }) { b, s -> b.setHostsBlacklist(s.hostsBlacklist) },
        slot({ it.hostsWhitelist }) { b, s -> b.setHostsWhitelist(s.hostsWhitelist) },
        slot({ it.desyncHttp }) { b, s -> b.setDesyncHttp(s.desyncHttp) },
        slot({ it.desyncHttps }) { b, s -> b.setDesyncHttps(s.desyncHttps) },
        slot({ it.desyncUdp }) { b, s -> b.setDesyncUdp(s.desyncUdp) },
        slot({ it.udpAssociateEnabled }) { b, s -> b.setUdpAssociateEnabled(s.udpAssociateEnabled) },
    )
