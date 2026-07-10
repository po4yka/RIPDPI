package com.poyka.ripdpi.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.WireFormat
import com.poyka.ripdpi.proto.AppSettings
import java.io.InputStream
import java.io.OutputStream

object AppSettingsSerializer : Serializer<AppSettings> {
    private val defaultDns = canonicalDefaultEncryptedDnsSettings()

    override val defaultValue: AppSettings =
        AppSettings
            .newBuilder()
            .setAppTheme("system")
            .setUiPersona("simple")
            .setRipdpiMode("vpn")
            .setDnsIp(defaultDns.dnsIp)
            .setDnsMode(defaultDns.mode)
            .setDnsProviderId(defaultDns.providerId)
            .setEncryptedDnsProtocol(defaultDns.encryptedDnsProtocol)
            .setEncryptedDnsHost(defaultDns.encryptedDnsHost)
            .setEncryptedDnsPort(defaultDns.encryptedDnsPort)
            .setEncryptedDnsTlsServerName(defaultDns.encryptedDnsTlsServerName)
            .addAllEncryptedDnsBootstrapIps(defaultDns.encryptedDnsBootstrapIps)
            .setEncryptedDnsDohUrl(defaultDns.encryptedDnsDohUrl)
            .setEncryptedDnsOdohProxyUrl("")
            .setEncryptedDnsOdohProxyOperatorId("")
            .setEncryptedDnsOdohTargetHost("")
            .setEncryptedDnsOdohTargetPath("")
            .setEncryptedDnsOdohTargetOperatorId("")
            .setEncryptedDnsOdohConfigSource("")
            .setEncryptedDnsOdohConfigsHex("")
            .setEncryptedDnsOdohConfigsRetrievedAtSecs(0L)
            .setEncryptedDnsOdohConfigsTtlSecs(0L)
            .setProxyIp("127.0.0.1")
            .setProxyPort(1080)
            .setMaxConnections(512)
            .setBufferSize(16384)
            .setFakeTtl(8)
            .setAdaptiveFakeTtlEnabled(false)
            .setAdaptiveFakeTtlDelta(DefaultAdaptiveFakeTtlDelta)
            .setAdaptiveFakeTtlMin(DefaultAdaptiveFakeTtlMin)
            .setAdaptiveFakeTtlMax(DefaultAdaptiveFakeTtlMax)
            .setAdaptiveFakeTtlFallback(DefaultAdaptiveFakeTtlFallback)
            .setFakeSni(DefaultFakeSni)
            .setFakeOffsetMarker(DefaultFakeOffsetMarker)
            .setFakeTlsSource(FakeTlsSourceProfile)
            .setFakeTlsSecondaryProfile("")
            .setFakeTcpTimestampEnabled(false)
            .setFakeTcpTimestampDeltaTicks(0)
            .setFakeTlsSniMode(FakeTlsSniModeFixed)
            .setHttpFakeProfile(FakePayloadProfileCompatDefault)
            .setTlsFakeProfile(FakePayloadProfileCompatDefault)
            .setOobData("a")
            .setDesyncHttp(true)
            .setDesyncHttps(true)
            .setDesyncUdp(true)
            .setDesyncAnyProtocol(false)
            .setUdpFakeProfile(FakePayloadProfileCompatDefault)
            .setHttpMethodEol(false)
            .setHttpUnixEol(false)
            .setHostsMode("disable")
            .setQuicInitialMode(QuicInitialModeRouteAndCache)
            .setQuicSupportV1(true)
            .setQuicSupportV2(true)
            .setQuicFakeProfile(QuicFakeProfileCompatDefault)
            .setQuicFakeHost("")
            .setQuicBindLowPort(false)
            .setQuicMigrateAfterHandshake(false)
            .setHostAutolearnEnabled(true)
            .setHostAutolearnPenaltyTtlHours(DefaultHostAutolearnPenaltyTtlHours)
            .setHostAutolearnMaxHosts(DefaultHostAutolearnMaxHosts)
            .setNetworkStrategyMemoryEnabled(true)
            .setAdaptiveFallbackEnabled(true)
            .setAdaptiveFallbackTorst(true)
            .setAdaptiveFallbackTlsErr(true)
            .setAdaptiveFallbackHttpRedirect(true)
            .setAdaptiveFallbackConnectFailure(true)
            .setAdaptiveFallbackAutoSort(true)
            .setAdaptiveFallbackCacheTtlSeconds(DefaultAdaptiveFallbackCacheTtlSeconds)
            .setAdaptiveFallbackCachePrefixV4(DefaultAdaptiveFallbackCachePrefixV4)
            .setStrategyEvolution(false)
            .setEvolutionEpsilon(DefaultEvolutionEpsilon)
            .setHttpMethodSpace(false)
            .setHttpHostPad(false)
            .setEntropyPaddingTargetPermil(0)
            .setEntropyPaddingMax(DefaultEntropyPaddingMax)
            .setEntropyMode(entropyModeToProto(EntropyModeDisabled))
            .setShannonEntropyTargetPermil(0)
            .setTlsFingerprintProfile(TlsFingerprintProfileChromeStable)
            .setStrategyPackChannel(DefaultStrategyPackChannel)
            .setStrategyPackPinnedId(DefaultStrategyPackPinnedId)
            .setStrategyPackPinnedVersion(DefaultStrategyPackPinnedVersion)
            .setStrategyPackRefreshPolicy(DefaultStrategyPackRefreshPolicy)
            .setStrategyPackAllowRollbackOverride(false)
            .setWarpEnabled(false)
            .setWarpRouteMode(WarpRouteModeOff)
            .setWarpRouteHosts("")
            .setWarpBuiltinRulesEnabled(true)
            .setWarpProfileId(DefaultWarpProfileId)
            .setWarpAccountKind(WarpAccountKindConsumerFree)
            .setWarpZeroTrustOrg("")
            .setWarpSetupState(WarpSetupStateNotConfigured)
            .setWarpLastScannerMode(WarpScannerModeAutomatic)
            .setWarpEndpointSelectionMode(WarpEndpointSelectionAutomatic)
            .setWarpManualEndpointPort(DefaultWarpManualEndpointPort)
            .setWarpScannerEnabled(false)
            .setWarpScannerParallelism(DefaultWarpScannerParallelism)
            .setWarpScannerMaxRttMs(DefaultWarpScannerMaxRttMs)
            .setRelayEnabled(false)
            .setRelayDnsOverTunnelEnabled(true)
            .setRelayKind(RelayKindOff)
            .setRelayProfileId(DefaultRelayProfileId)
            .setRelayCloudflareTunnelMode(RelayCloudflareTunnelModeConsumeExisting)
            .setRelayServerPort(443)
            .setRelayChainEntryPort(443)
            .setRelayChainExitPort(443)
            .setRelayMasqueUseHttp2Fallback(true)
            .setRelayOutboundBindIp("")
            .setRelayLocalSocksHost(DefaultRelayLocalSocksHost)
            .setRelayLocalSocksPort(DefaultRelayLocalSocksPort)
            .setRelayUdpEnabled(false)
            .setRelayTcpFallbackEnabled(true)
            .setRelayFinalmaskType(RelayFinalmaskTypeOff)
            .setRelayAppsScriptVerifySsl(DefaultRelayAppsScriptVerifySsl)
            .setAppIconVariant("default")
            .setAppIconStyle("themed")
            .setSimpleFailoverAwgProfileId("")
            .setDiagnosticsMonitorEnabled(true)
            .setDiagnosticsSampleIntervalSeconds(15)
            .setDiagnosticsDefaultScanPathMode("raw_path")
            .setDiagnosticsAutoResumeAfterRawScan(true)
            .setDiagnosticsActiveProfileId("default")
            .setDiagnosticsHistoryRetentionDays(14)
            .setDiagnosticsExportIncludeHistory(true)
            .setCanonicalDefaultStrategyChains()
            .setDetectionCheckColorVisionMode("off")
            .setDetectionCheckProtanopiaVariantUnlocked(false)
            .setDetectionCheckNetworkRequestsEnabled(true)
            .setDetectionCheckCdnPullingMeduzaEnabled(true)
            .setDetectionCheckCallTransportProbeEnabled(false)
            .setDetectionCheckXrayApiScanEnabled(true)
            .setDetectionCheckTunProbeMode("auto")
            .setDetectionCheckPortRangeMode("popular")
            .setDetectionCheckCustomPortStart(1080)
            .setDetectionCheckCustomPortEnd(1090)
            .setDetectionCheckDnsResolverMode("system")
            .setDetectionCheckDnsPreset("custom")
            .setDetectionCheckDnsDirectServers("")
            .setDetectionCheckDnsDohUrl("")
            .setDetectionCheckDnsDohBootstrapIps("")
            .setDpiSuiteConcurrency(100)
            .setDetectionDiagnosticRandomHostnamesEnabled(false)
            .setDetectionDiagnosticTlsKeylogPath("")
            .setExcludeRussianAppsEnabled(true)
            .setAppRoutingPolicyMode(AppRoutingPolicyModePrompt)
            .addAppRoutingEnabledPresetIds(DefaultAppRoutingRussianPresetId)
            .setAntiCorrelationEnabled(false)
            .setDhtMitigationMode(DhtMitigationModeOff)
            .setFullTunnelMode(false)
            .setStartOnBoot(false)
            .build()

    override suspend fun readFrom(input: InputStream): AppSettings {
        try {
            val bytes = input.readBytes()
            return migrateLegacyRelayXhttpFields(AppSettings.parseFrom(bytes), bytes)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", e)
        }
    }

    override suspend fun writeTo(
        t: AppSettings,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }

    private fun migrateLegacyRelayXhttpFields(
        settings: AppSettings,
        bytes: ByteArray,
    ): AppSettings {
        val legacy = readLegacyRelayXhttpFields(bytes)
        val legacyTransport = legacy.transport?.takeIf { it == "xhttp" || it == "reality_tcp" }
        val hasLegacyCompanionField = legacy.path != null || legacy.host != null
        val compatibleRelay =
            settings.relayEnabled &&
                (settings.relayKind == RelayKindVless || settings.relayKind == RelayKindVlessReality)
        val isLegacyPayload = hasLegacyCompanionField || (legacyTransport != null && compatibleRelay)
        if (!isLegacyPayload) return settings

        return settings
            .toBuilder()
            .apply {
                if (settings.relayVlessTransport.isEmpty() && legacyTransport != null) {
                    relayVlessTransport = legacyTransport
                }
                if (settings.relayXhttpPath.isEmpty() && legacy.path != null) {
                    relayXhttpPath = legacy.path
                }
                if (settings.relayXhttpHost.isEmpty() && legacy.host != null) {
                    relayXhttpHost = legacy.host
                }
                if (legacyTransport != null && settings.strategyChainYaml == legacyTransport) {
                    clearStrategyChainYaml()
                }
            }.build()
    }

    private fun readLegacyRelayXhttpFields(bytes: ByteArray): LegacyRelayXhttpFields {
        val input = CodedInputStream.newInstance(bytes)
        var transport: String? = null
        var path: String? = null
        var host: String? = null
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED || fieldNumber !in 214..216) {
                input.skipField(tag)
                continue
            }
            when (fieldNumber) {
                214 -> transport = input.readStringRequireUtf8()
                215 -> path = input.readStringRequireUtf8()
                216 -> host = input.readStringRequireUtf8()
            }
        }
        return LegacyRelayXhttpFields(transport = transport, path = path, host = host)
    }

    private data class LegacyRelayXhttpFields(
        val transport: String?,
        val path: String?,
        val host: String?,
    )
}
