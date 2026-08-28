package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelaySettingsModel
import com.poyka.ripdpi.data.normalizeRelayCloudflareTunnelMode
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayFinalmaskType
import com.poyka.ripdpi.data.normalizeRelayXhttpMode
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.collections.immutable.toImmutableList

internal fun ConfigDraft.withRelaySettings(relay: RelaySettingsModel): ConfigDraft =
    copy(
        relayEnabled = relay.enabled,
        relayKind = relay.kind,
        relayProfileId = relay.profileId,
        relayPresetId = relay.profile.presetId,
        relayServer = relay.profile.server,
        relayServerPort = relay.profile.serverPort.toString(),
        relayServerName = relay.profile.serverName,
        relayRealityPublicKey = relay.profile.realityPublicKey,
        relayRealityShortId = relay.profile.realityShortId,
        relayVlessTransport = relay.profile.vlessTransport,
        relayXhttpPath = relay.profile.xhttpPath,
        relayXhttpHost = relay.profile.xhttpHost,
        relayXhttpMode = relay.profile.xhttpMode,
        relayCloudflareTunnelMode = relay.profile.cloudflareTunnelMode,
        relayCloudflarePublishLocalOriginUrl = relay.profile.cloudflarePublishLocalOriginUrl,
        relayCloudflareCredentialsRef = relay.profile.cloudflareCredentialsRef,
        relayChainEntryServer = relay.profile.chainEntryServer,
        relayChainEntryPort = relay.profile.chainEntryPort.toString(),
        relayChainEntryServerName = relay.profile.chainEntryServerName,
        relayChainEntryPublicKey = relay.profile.chainEntryPublicKey,
        relayChainEntryShortId = relay.profile.chainEntryShortId,
        relayChainEntryProfileId = relay.profile.chainEntryProfileId,
        relayChainExitServer = relay.profile.chainExitServer,
        relayChainExitPort = relay.profile.chainExitPort.toString(),
        relayChainExitServerName = relay.profile.chainExitServerName,
        relayChainExitPublicKey = relay.profile.chainExitPublicKey,
        relayChainExitShortId = relay.profile.chainExitShortId,
        relayChainExitProfileId = relay.profile.chainExitProfileId,
        relayChainMiddleProfileIds = relay.profile.chainMiddleProfileIds.toImmutableList(),
        relayMasqueUrl = relay.profile.masqueUrl,
        relayMasqueUseHttp2Fallback = relay.profile.masqueUseHttp2Fallback,
        relayMasqueCloudflareGeohashEnabled = relay.profile.masqueCloudflareGeohashEnabled,
        relayTuicZeroRtt = relay.profile.tuicZeroRtt,
        relayTuicCongestionControl = relay.profile.tuicCongestionControl,
        relayShadowTlsInnerProfileId = relay.profile.shadowTlsInnerProfileId,
        relayNaivePath = relay.profile.naivePath,
        relayPtBridgeLine = relay.profile.ptBridgeLine,
        relayWebTunnelUrl = relay.profile.ptWebTunnelUrl,
        relaySnowflakeBrokerUrl = relay.profile.ptSnowflakeBrokerUrl,
        relaySnowflakeFrontDomain = relay.profile.ptSnowflakeFrontDomain,
        relayUdpEnabled = relay.profile.udpEnabled,
        relayLocalSocksPort = relay.profile.localSocksPort.toString(),
        relayFinalmaskType = relay.profile.finalmask.type,
        relayFinalmaskHeaderHex = relay.profile.finalmask.headerHex,
        relayFinalmaskTrailerHex = relay.profile.finalmask.trailerHex,
        relayFinalmaskRandRange = relay.profile.finalmask.randRange,
        relayFinalmaskSudokuSeed = relay.profile.finalmask.sudokuSeed,
        relayFinalmaskFragmentPackets =
            relay.profile.finalmask.fragmentPackets
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty(),
        relayFinalmaskFragmentMinBytes =
            relay.profile.finalmask.fragmentMinBytes
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty(),
        relayFinalmaskFragmentMaxBytes =
            relay.profile.finalmask.fragmentMaxBytes
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty(),
    )

internal fun AppSettings.Builder.applyConfigDraft(draft: ConfigDraft): AppSettings.Builder =
    apply {
        val defaults = AppSettingsSerializer.defaultValue
        setRipdpiMode(draft.mode.preferenceValue)
        setDnsIp(draft.dnsIp.ifBlank { defaults.dnsIp })
        setEnableCmdSettings(draft.useCommandLineSettings)
        setCmdArgs(draft.commandLineArgs)
        setProxyIp(draft.proxyIp.ifBlank { defaults.proxyIp })
        setProxyPort(draft.proxyPort.toIntOrNull() ?: defaults.proxyPort)
        setMaxConnections(draft.maxConnections.toIntOrNull() ?: defaults.maxConnections)
        setBufferSize(draft.bufferSize.toIntOrNull() ?: defaults.bufferSize)
        val chains = draft.resolvedChainSet()
        setStrategyChains(chains.tcpSteps, chains.udpSteps)
        setCustomTtl(draft.defaultTtl.isNotBlank())
        setDefaultTtl(draft.defaultTtl.toIntOrNull() ?: 0)
        applyRelayDraft(draft)
    }

private fun AppSettings.Builder.applyRelayDraft(draft: ConfigDraft): AppSettings.Builder =
    apply {
        setRelayEnabled(draft.relayEnabled && draft.relayKind != RelayKindOff)
        setRelayKind(draft.relayKind)
        setRelayProfileId(draft.relayProfileId.ifBlank { DefaultRelayProfileId })
        setRelayServer(draft.relayServer)
        setRelayServerPort(draft.relayServerPort.toIntOrNull() ?: defaultRelayPort)
        setRelayServerName(
            when (draft.relayKind) {
                RelayKindCloudflareTunnel, RelayKindAnyTls -> draft.relayServerName.ifBlank { draft.relayServer }
                else -> draft.relayServerName
            },
        )
        setRelayRealityPublicKey(draft.relayRealityPublicKey)
        setRelayRealityShortId(draft.relayRealityShortId)
        setRelayVlessTransport(draft.relayVlessTransport)
        setRelayXhttpPath(draft.relayXhttpPath)
        setRelayXhttpHost(draft.relayXhttpHost)
        setRelayXhttpMode(normalizeRelayXhttpMode(draft.relayXhttpMode))
        setRelayCloudflareTunnelMode(normalizeRelayCloudflareTunnelMode(draft.relayCloudflareTunnelMode))
        setRelayCloudflarePublishLocalOriginUrl(draft.relayCloudflarePublishLocalOriginUrl)
        setRelayCloudflareCredentialsRef(draft.relayCloudflareCredentialsRef)
        setRelayChainEntryServer("")
        setRelayChainEntryPort(defaultRelayPort)
        setRelayChainEntryServerName("")
        setRelayChainEntryPublicKey("")
        setRelayChainEntryShortId("")
        setRelayChainEntryProfileId(if (draft.relayKind == RelayKindChainRelay) draft.relayChainEntryProfileId else "")
        setRelayChainExitServer("")
        setRelayChainExitPort(defaultRelayPort)
        setRelayChainExitServerName("")
        setRelayChainExitPublicKey("")
        setRelayChainExitShortId("")
        setRelayChainExitProfileId(if (draft.relayKind == RelayKindChainRelay) draft.relayChainExitProfileId else "")
        clearRelayChainMiddleProfileIds()
        if (draft.relayKind == RelayKindChainRelay) {
            addAllRelayChainMiddleProfileIds(draft.relayChainMiddleProfileIds)
        }
        setRelayMasqueUrl(draft.relayMasqueUrl)
        setRelayMasqueUseHttp2Fallback(draft.relayMasqueUseHttp2Fallback)
        setRelayMasqueCloudflareGeohashEnabled(draft.relayMasqueCloudflareGeohashEnabled)
        setRelayTuicZeroRtt(draft.relayTuicZeroRtt)
        setRelayTuicCongestionControl(normalizeRelayCongestionControl(draft.relayTuicCongestionControl))
        setRelayShadowtlsInnerProfileId(draft.relayShadowTlsInnerProfileId)
        setRelayNaivePath(draft.relayNaivePath)
        setRelayLocalSocksHost("127.0.0.1")
        setRelayLocalSocksPort(draft.relayLocalSocksPort.toIntOrNull() ?: DefaultRelayLocalSocksPort)
        setRelayUdpEnabled(draft.relayUdpEnabled && draft.relayKind.supportsRelayUdpMode())
        setRelayTcpFallbackEnabled(draft.relayMasqueUseHttp2Fallback)
        setRelayFinalmaskType(normalizeRelayFinalmaskType(draft.relayFinalmaskType))
        setRelayFinalmaskHeaderHex(draft.relayFinalmaskHeaderHex)
        setRelayFinalmaskTrailerHex(draft.relayFinalmaskTrailerHex)
        setRelayFinalmaskRandRange(draft.relayFinalmaskRandRange)
        setRelayFinalmaskSudokuSeed(draft.relayFinalmaskSudokuSeed)
        setRelayFinalmaskFragmentPackets(draft.relayFinalmaskFragmentPackets.toIntOrNull() ?: 0)
        setRelayFinalmaskFragmentMinBytes(draft.relayFinalmaskFragmentMinBytes.toIntOrNull() ?: 0)
        setRelayFinalmaskFragmentMaxBytes(draft.relayFinalmaskFragmentMaxBytes.toIntOrNull() ?: 0)
    }
