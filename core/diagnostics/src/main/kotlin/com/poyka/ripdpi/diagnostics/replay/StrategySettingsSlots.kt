package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.proto.AppSettings

/**
 * One AppSettings field the proxy-preferences strategy writer sets, with a reader and a copier.
 * The list mirrors the desync, fake-packet, parser, adaptive, QUIC, host and protocol-toggle
 * sections of `applyToSettings`, plus the listen-section fields that change what the replayed
 * connection puts on the wire: TCP Fast Open (SynData chains), the custom/default TTL of real
 * packets, freeze detection (it triggers adaptive fallback) and domain resolution (`noDomain`).
 *
 * Excluded on purpose: listen address, port, max connections and buffer size (the replay must not
 * move the proxy); relay, WARP, AWG and ws-tunnel fields (other transports); host-autolearn
 * fields (learned state, not strategy). A writer field missing from this list is never applied,
 * so add new strategy fields from `RipDpiProxyUIPreferencesSettingsWriter` here as well.
 */
internal class StrategySettingsSlot(
    val name: String,
    val read: (AppSettings) -> Any?,
    val copy: (AppSettings.Builder, AppSettings) -> Unit,
)

private fun slot(
    name: String,
    read: (AppSettings) -> Any?,
    copy: (AppSettings.Builder, AppSettings) -> Unit,
) = StrategySettingsSlot(name, read, copy)

internal val StrategySettingsSlots: List<StrategySettingsSlot> =
    listOf(
        slot(
            "tcpChainSteps",
            { it.tcpChainStepsList },
            { b, s -> b.clearTcpChainSteps().addAllTcpChainSteps(s.tcpChainStepsList) },
        ),
        slot(
            "udpChainSteps",
            { it.udpChainStepsList },
            { b, s -> b.clearUdpChainSteps().addAllUdpChainSteps(s.udpChainStepsList) },
        ),
        slot(
            "groupActivationFilter",
            { if (it.hasGroupActivationFilter()) it.groupActivationFilter else null },
        ) { b, s ->
            if (s.hasGroupActivationFilter()) {
                b.setGroupActivationFilter(s.groupActivationFilter)
            } else {
                b.clearGroupActivationFilter()
            }
        },
        slot("desyncAnyProtocol", { it.desyncAnyProtocol }) { b, s -> b.setDesyncAnyProtocol(s.desyncAnyProtocol) },
        slot("fakeTtl", { it.fakeTtl }) { b, s -> b.setFakeTtl(s.fakeTtl) },
        slot(
            "adaptiveFakeTtlEnabled",
            { it.adaptiveFakeTtlEnabled },
            { b, s -> b.setAdaptiveFakeTtlEnabled(s.adaptiveFakeTtlEnabled) },
        ),
        slot(
            "adaptiveFakeTtlDelta",
            { it.adaptiveFakeTtlDelta },
            { b, s -> b.setAdaptiveFakeTtlDelta(s.adaptiveFakeTtlDelta) },
        ),
        slot("adaptiveFakeTtlMin", { it.adaptiveFakeTtlMin }) { b, s -> b.setAdaptiveFakeTtlMin(s.adaptiveFakeTtlMin) },
        slot("adaptiveFakeTtlMax", { it.adaptiveFakeTtlMax }) { b, s -> b.setAdaptiveFakeTtlMax(s.adaptiveFakeTtlMax) },
        slot(
            "adaptiveFakeTtlFallback",
            { it.adaptiveFakeTtlFallback },
            { b, s -> b.setAdaptiveFakeTtlFallback(s.adaptiveFakeTtlFallback) },
        ),
        slot("fakeSni", { it.fakeSni }) { b, s -> b.setFakeSni(s.fakeSni) },
        slot("fakeTlsSource", { it.fakeTlsSource }) { b, s -> b.setFakeTlsSource(s.fakeTlsSource) },
        slot(
            "fakeTlsSecondaryProfile",
            { it.fakeTlsSecondaryProfile },
            { b, s -> b.setFakeTlsSecondaryProfile(s.fakeTlsSecondaryProfile) },
        ),
        slot(
            "fakeTcpTimestampEnabled",
            { it.fakeTcpTimestampEnabled },
            { b, s -> b.setFakeTcpTimestampEnabled(s.fakeTcpTimestampEnabled) },
        ),
        slot(
            "fakeTcpTimestampDeltaTicks",
            { it.fakeTcpTimestampDeltaTicks },
            { b, s -> b.setFakeTcpTimestampDeltaTicks(s.fakeTcpTimestampDeltaTicks) },
        ),
        slot("httpFakeProfile", { it.httpFakeProfile }) { b, s -> b.setHttpFakeProfile(s.httpFakeProfile) },
        slot("fakeTlsUseOriginal", { it.fakeTlsUseOriginal }) { b, s -> b.setFakeTlsUseOriginal(s.fakeTlsUseOriginal) },
        slot("fakeTlsRandomize", { it.fakeTlsRandomize }) { b, s -> b.setFakeTlsRandomize(s.fakeTlsRandomize) },
        slot(
            "fakeTlsDupSessionId",
            { it.fakeTlsDupSessionId },
            { b, s -> b.setFakeTlsDupSessionId(s.fakeTlsDupSessionId) },
        ),
        slot("fakeTlsPadEncap", { it.fakeTlsPadEncap }) { b, s -> b.setFakeTlsPadEncap(s.fakeTlsPadEncap) },
        slot("fakeTlsSize", { it.fakeTlsSize }) { b, s -> b.setFakeTlsSize(s.fakeTlsSize) },
        slot("fakeTlsSniMode", { it.fakeTlsSniMode }) { b, s -> b.setFakeTlsSniMode(s.fakeTlsSniMode) },
        slot("tlsFakeProfile", { it.tlsFakeProfile }) { b, s -> b.setTlsFakeProfile(s.tlsFakeProfile) },
        slot("udpFakeProfile", { it.udpFakeProfile }) { b, s -> b.setUdpFakeProfile(s.udpFakeProfile) },
        slot("fakeOffsetMarker", { it.fakeOffsetMarker }) { b, s -> b.setFakeOffsetMarker(s.fakeOffsetMarker) },
        slot("oobData", { it.oobData }) { b, s -> b.setOobData(s.oobData) },
        slot("dropSack", { it.dropSack }) { b, s -> b.setDropSack(s.dropSack) },
        slot("fakeMd5Sig", { it.fakeMd5Sig }) { b, s -> b.setFakeMd5Sig(s.fakeMd5Sig) },
        slot("tlsMinorEnabled", { it.tlsMinorEnabled }) { b, s -> b.setTlsMinorEnabled(s.tlsMinorEnabled) },
        slot("tlsMinorValue", { it.tlsMinorValue }) { b, s -> b.setTlsMinorValue(s.tlsMinorValue) },
        slot("windowClamp", { it.windowClamp }) { b, s -> b.setWindowClamp(s.windowClamp) },
        slot("wsizeWindow", { it.wsizeWindow }) { b, s -> b.setWsizeWindow(s.wsizeWindow) },
        slot("wsizeScale", { it.wsizeScale }) { b, s -> b.setWsizeScale(s.wsizeScale) },
        slot("stripTimestamps", { it.stripTimestamps }) { b, s -> b.setStripTimestamps(s.stripTimestamps) },
        slot("ipIdMode", { it.ipIdMode }) { b, s -> b.setIpIdMode(s.ipIdMode) },
        slot("quicBindLowPort", { it.quicBindLowPort }) { b, s -> b.setQuicBindLowPort(s.quicBindLowPort) },
        slot(
            "quicMigrateAfterHandshake",
            { it.quicMigrateAfterHandshake },
            { b, s -> b.setQuicMigrateAfterHandshake(s.quicMigrateAfterHandshake) },
        ),
        slot("entropyMode", { it.entropyMode }) { b, s -> b.setEntropyMode(s.entropyMode) },
        slot(
            "entropyPaddingTargetPermil",
            { it.entropyPaddingTargetPermil },
            { b, s -> b.setEntropyPaddingTargetPermil(s.entropyPaddingTargetPermil) },
        ),
        slot("entropyPaddingMax", { it.entropyPaddingMax }) { b, s -> b.setEntropyPaddingMax(s.entropyPaddingMax) },
        slot(
            "shannonEntropyTargetPermil",
            { it.shannonEntropyTargetPermil },
            { b, s -> b.setShannonEntropyTargetPermil(s.shannonEntropyTargetPermil) },
        ),
        slot(
            "tlsFingerprintProfile",
            { it.tlsFingerprintProfile },
            { b, s -> b.setTlsFingerprintProfile(s.tlsFingerprintProfile) },
        ),
        slot("hostMixedCase", { it.hostMixedCase }) { b, s -> b.setHostMixedCase(s.hostMixedCase) },
        slot("domainMixedCase", { it.domainMixedCase }) { b, s -> b.setDomainMixedCase(s.domainMixedCase) },
        slot("hostRemoveSpaces", { it.hostRemoveSpaces }) { b, s -> b.setHostRemoveSpaces(s.hostRemoveSpaces) },
        slot("httpMethodEol", { it.httpMethodEol }) { b, s -> b.setHttpMethodEol(s.httpMethodEol) },
        slot("httpMethodSpace", { it.httpMethodSpace }) { b, s -> b.setHttpMethodSpace(s.httpMethodSpace) },
        slot("httpUnixEol", { it.httpUnixEol }) { b, s -> b.setHttpUnixEol(s.httpUnixEol) },
        slot("httpHostPad", { it.httpHostPad }) { b, s -> b.setHttpHostPad(s.httpHostPad) },
        slot("httpHostExtraSpace", { it.httpHostExtraSpace }) { b, s -> b.setHttpHostExtraSpace(s.httpHostExtraSpace) },
        slot("httpHostTab", { it.httpHostTab }) { b, s -> b.setHttpHostTab(s.httpHostTab) },
        slot(
            "adaptiveFallbackEnabled",
            { it.adaptiveFallbackEnabled },
            { b, s -> b.setAdaptiveFallbackEnabled(s.adaptiveFallbackEnabled) },
        ),
        slot(
            "adaptiveFallbackTorst",
            { it.adaptiveFallbackTorst },
            { b, s -> b.setAdaptiveFallbackTorst(s.adaptiveFallbackTorst) },
        ),
        slot(
            "adaptiveFallbackTlsErr",
            { it.adaptiveFallbackTlsErr },
            { b, s -> b.setAdaptiveFallbackTlsErr(s.adaptiveFallbackTlsErr) },
        ),
        slot(
            "adaptiveFallbackHttpRedirect",
            { it.adaptiveFallbackHttpRedirect },
            { b, s -> b.setAdaptiveFallbackHttpRedirect(s.adaptiveFallbackHttpRedirect) },
        ),
        slot(
            "adaptiveFallbackConnectFailure",
            { it.adaptiveFallbackConnectFailure },
            { b, s -> b.setAdaptiveFallbackConnectFailure(s.adaptiveFallbackConnectFailure) },
        ),
        slot(
            "adaptiveFallbackAutoSort",
            { it.adaptiveFallbackAutoSort },
            { b, s -> b.setAdaptiveFallbackAutoSort(s.adaptiveFallbackAutoSort) },
        ),
        slot(
            "adaptiveFallbackCacheTtlSeconds",
            { it.adaptiveFallbackCacheTtlSeconds },
            { b, s -> b.setAdaptiveFallbackCacheTtlSeconds(s.adaptiveFallbackCacheTtlSeconds) },
        ),
        slot(
            "adaptiveFallbackCachePrefixV4",
            { it.adaptiveFallbackCachePrefixV4 },
            { b, s -> b.setAdaptiveFallbackCachePrefixV4(s.adaptiveFallbackCachePrefixV4) },
        ),
        slot("strategyEvolution", { it.strategyEvolution }) { b, s -> b.setStrategyEvolution(s.strategyEvolution) },
        slot("evolutionEpsilon", { it.evolutionEpsilon }) { b, s -> b.setEvolutionEpsilon(s.evolutionEpsilon) },
        slot(
            "evolutionExperimentTtlMs",
            { it.evolutionExperimentTtlMs },
            { b, s -> b.setEvolutionExperimentTtlMs(s.evolutionExperimentTtlMs) },
        ),
        slot(
            "evolutionDecayHalfLifeMs",
            { it.evolutionDecayHalfLifeMs },
            { b, s -> b.setEvolutionDecayHalfLifeMs(s.evolutionDecayHalfLifeMs) },
        ),
        slot(
            "evolutionCooldownAfterFailures",
            { it.evolutionCooldownAfterFailures },
            { b, s -> b.setEvolutionCooldownAfterFailures(s.evolutionCooldownAfterFailures) },
        ),
        slot(
            "evolutionCooldownMs",
            { it.evolutionCooldownMs },
            { b, s -> b.setEvolutionCooldownMs(s.evolutionCooldownMs) },
        ),
        slot("quicInitialMode", { it.quicInitialMode }) { b, s -> b.setQuicInitialMode(s.quicInitialMode) },
        slot("quicSupportV1", { it.quicSupportV1 }) { b, s -> b.setQuicSupportV1(s.quicSupportV1) },
        slot("quicSupportV2", { it.quicSupportV2 }) { b, s -> b.setQuicSupportV2(s.quicSupportV2) },
        slot("quicFakeProfile", { it.quicFakeProfile }) { b, s -> b.setQuicFakeProfile(s.quicFakeProfile) },
        slot("quicFakeHost", { it.quicFakeHost }) { b, s -> b.setQuicFakeHost(s.quicFakeHost) },
        slot("hostsMode", { it.hostsMode }) { b, s -> b.setHostsMode(s.hostsMode) },
        slot("hostsBlacklist", { it.hostsBlacklist }) { b, s -> b.setHostsBlacklist(s.hostsBlacklist) },
        slot("hostsWhitelist", { it.hostsWhitelist }) { b, s -> b.setHostsWhitelist(s.hostsWhitelist) },
        slot("desyncHttp", { it.desyncHttp }) { b, s -> b.setDesyncHttp(s.desyncHttp) },
        slot("desyncHttps", { it.desyncHttps }) { b, s -> b.setDesyncHttps(s.desyncHttps) },
        slot("desyncUdp", { it.desyncUdp }) { b, s -> b.setDesyncUdp(s.desyncUdp) },
        slot(
            "udpAssociateEnabled",
            { it.udpAssociateEnabled },
            { b, s -> b.setUdpAssociateEnabled(s.udpAssociateEnabled) },
        ),
        slot("tcpFastOpen", { it.tcpFastOpen }) { b, s -> b.setTcpFastOpen(s.tcpFastOpen) },
        slot("customTtl", { it.customTtl }) { b, s -> b.setCustomTtl(s.customTtl) },
        slot("defaultTtl", { it.defaultTtl }) { b, s -> b.setDefaultTtl(s.defaultTtl) },
        slot(
            "freezeDetectionEnabled",
            { it.freezeDetectionEnabled },
            { b, s -> b.setFreezeDetectionEnabled(s.freezeDetectionEnabled) },
        ),
        slot("noDomain", { it.noDomain }) { b, s -> b.setNoDomain(s.noDomain) },
    )
