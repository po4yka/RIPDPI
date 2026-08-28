package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

internal fun AppSettings.Builder.applyRelayAfterImage(
    profile: RelayProfileRecord,
    enabled: Boolean,
) {
    setRelayEnabled(enabled && profile.kind != RelayKindOff)
    setRelayKind(profile.kind)
    setRelayProfileId(profile.id)
    setRelayServer(profile.server)
    setRelayServerPort(profile.serverPort)
    setRelayServerName(profile.serverName)
    setRelayRealityPublicKey(profile.realityPublicKey)
    setRelayRealityShortId(profile.realityShortId)
    setRelayChainEntryServer(profile.chainEntryServer)
    setRelayChainEntryPort(profile.chainEntryPort)
    setRelayChainEntryServerName(profile.chainEntryServerName)
    setRelayChainEntryPublicKey(profile.chainEntryPublicKey)
    setRelayChainEntryShortId(profile.chainEntryShortId)
    setRelayChainEntryProfileId(profile.chainEntryProfileId)
    setRelayChainExitServer(profile.chainExitServer)
    setRelayChainExitPort(profile.chainExitPort)
    setRelayChainExitServerName(profile.chainExitServerName)
    setRelayChainExitPublicKey(profile.chainExitPublicKey)
    setRelayChainExitShortId(profile.chainExitShortId)
    setRelayChainExitProfileId(profile.chainExitProfileId)
    clearRelayChainMiddleProfileIds()
    addAllRelayChainMiddleProfileIds(profile.chainMiddleProfileIds)
    setRelayMasqueUrl(profile.masqueUrl)
    setRelayMasqueUseHttp2Fallback(profile.masqueUseHttp2Fallback)
    setRelayMasqueCloudflareGeohashEnabled(profile.masqueCloudflareGeohashEnabled)
    setRelayCloudflareTunnelMode(profile.cloudflareTunnelMode)
    setRelayCloudflarePublishLocalOriginUrl(profile.cloudflarePublishLocalOriginUrl)
    setRelayCloudflareCredentialsRef(profile.cloudflareCredentialsRef)
    setRelayTuicZeroRtt(profile.tuicZeroRtt)
    setRelayTuicCongestionControl(profile.tuicCongestionControl)
    setRelayShadowtlsInnerProfileId(profile.shadowTlsInnerProfileId)
    setRelayNaivePath(profile.naivePath)
    setRelayLocalSocksHost(profile.localSocksHost)
    setRelayLocalSocksPort(profile.localSocksPort)
    setRelayUdpEnabled(profile.udpEnabled)
    setRelayTcpFallbackEnabled(profile.tcpFallbackEnabled)
    setRelayFinalmaskType(profile.finalmaskType)
    setRelayFinalmaskHeaderHex(profile.finalmaskHeaderHex)
    setRelayFinalmaskTrailerHex(profile.finalmaskTrailerHex)
    setRelayFinalmaskRandRange(profile.finalmaskRandRange)
    setRelayFinalmaskSudokuSeed(profile.finalmaskSudokuSeed)
    setRelayFinalmaskFragmentPackets(profile.finalmaskFragmentPackets)
    setRelayFinalmaskFragmentMinBytes(profile.finalmaskFragmentMinBytes)
    setRelayFinalmaskFragmentMaxBytes(profile.finalmaskFragmentMaxBytes)
    clearRelayAppsScriptScriptIds()
    addAllRelayAppsScriptScriptIds(profile.appsScriptScriptIds)
    setRelayAppsScriptGoogleIp(profile.appsScriptGoogleIp)
    setRelayAppsScriptFrontDomain(profile.appsScriptFrontDomain)
    setRelayAppsScriptVerifySsl(profile.appsScriptVerifySsl)
    setRelayAppsScriptParallelRelay(profile.appsScriptParallelRelay)
    clearRelayAppsScriptSniHosts()
    addAllRelayAppsScriptSniHosts(profile.appsScriptSniHosts)
    clearRelayAppsScriptDirectHosts()
    addAllRelayAppsScriptDirectHosts(profile.appsScriptDirectHosts)
    setRelayVlessTransport(profile.vlessTransport)
    setRelayXhttpPath(profile.xhttpPath)
    setRelayXhttpHost(profile.xhttpHost)
    setRelayXhttpMode(profile.xhttpMode)
    setRelayMieruProtocol(profile.mieruProtocol)
    setRelayMieruMultiplexing(profile.mieruMultiplexing)
    setRelayMieruMtu(profile.mieruMtu)
    setRelaySshAuthType(profile.sshAuthType)
    setRelaySshHostKeyFingerprint(profile.sshHostKeyFingerprint)
    setRelaySshStrictHostKey(profile.sshStrictHostKey)
    setRelayOutboundBindIp(profile.outboundBindIp)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileMutationCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindProfileMutationCoordinator(
        coordinator: ProfileMutationRecoveryCoordinator,
    ): ProfileMutationCoordinator

    @Binds
    @Singleton
    abstract fun bindXrayProviderMutationCoordinator(
        coordinator: ProfileMutationRecoveryCoordinator,
    ): XrayProviderMutationCoordinator
}
