package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelaySecurityLayerReality
import com.poyka.ripdpi.data.RelaySecurityLayerTls
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.validateNativeRelayProfile
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists a parsed or edited [ProxyProfile] as the active native relay.
 *
 * Writes the non-secret [RelayProfileRecord], the secure [RelayCredentialRecord],
 * and the AppSettings relay fields the runtime resolver
 * (`UpstreamRelayRuntimeConfigResolver`) reads to build the native wire config.
 * Shared by the import-confirmation surface and the dedicated profile editors so
 * both reach an identical working tunnel rather than duplicating the mapping.
 *
 * [activate] returns `true` when [profile] is a relay-activatable kind and was
 * applied, `false` (a no-op) for kinds that are not relay outbounds. The optional
 * `profileId` defaults to [DefaultRelayProfileId] (the single active-relay slot);
 * callers that must keep several relay profiles side by side in the store — e.g.
 * the simple-flavor seeder building a multi-transport failover set — pass a
 * distinct stable id per profile so they do not overwrite one another.
 */
@Singleton
class RelayProfileActivator
    @Inject
    constructor(
        private val profileMutations: ProfileMutationCoordinator,
    ) {
        constructor(
            relayProfileStore: RelayProfileStore,
            relayCredentialStore: RelayCredentialStore,
            settingsRepository: AppSettingsRepository,
        ) : this(DirectRelayProfileMutationCoordinator(relayProfileStore, relayCredentialStore, settingsRepository))

        suspend fun activate(
            profile: ProxyProfile,
            profileId: String = DefaultRelayProfileId,
            tlsFingerprintOverride: String? = null,
            modeAfterImage: String? = null,
            xraySelectionAfterImage: XrayProviderSelectionRecord? = null,
        ): Boolean {
            val relayKind = relayKindFor(profile)
            return if (relayKind == null || !validateNativeRelayProfile(profile)) {
                false
            } else {
                persistRelayActivation(
                    profile = profile,
                    profileId = profileId,
                    relayKind = relayKind,
                    tlsFingerprintOverride = tlsFingerprintOverride,
                    modeAfterImage = modeAfterImage,
                    xraySelectionAfterImage = xraySelectionAfterImage,
                )
                true
            }
        }

        private suspend fun persistRelayActivation(
            profile: ProxyProfile,
            profileId: String,
            relayKind: String,
            tlsFingerprintOverride: String?,
            modeAfterImage: String?,
            xraySelectionAfterImage: XrayProviderSelectionRecord?,
        ) {
            val endpoint = relayEndpoint(profile)
            val udpEnabled = relayUdpEnabled(profile)
            val vlessTransport =
                if (profile.hasXhttpTransport()) {
                    RelayVlessTransportXhttp
                } else {
                    RelayVlessTransportRealityTcp
                }
            val relayProfile =
                RelayProfileRecord(
                    id = profileId,
                    kind = relayKind,
                    server = endpoint.server,
                    serverPort = endpoint.serverPort,
                    serverName = endpoint.serverName,
                    securityLayer =
                        if (profile is ProxyProfile.Vless) {
                            RelaySecurityLayerTls
                        } else {
                            RelaySecurityLayerReality
                        },
                    realityPublicKey = if (profile is ProxyProfile.VlessReality) profile.realityPublicKey else "",
                    realityShortId = if (profile is ProxyProfile.VlessReality) profile.realityShortId else "",
                    vlessFlow = profile.vlessFlow(),
                    vlessFingerprint = tlsFingerprintOverride ?: profile.vlessFingerprint(),
                    vlessTransport = vlessTransport,
                    xhttpPath = profile.vlessXhttpPath(),
                    xhttpHost = profile.vlessXhttpHost(),
                    xhttpMode = profile.vlessXhttpMode(),
                    mieruProtocol = if (profile is ProxyProfile.Mieru) profile.protocol else "tcp",
                    mieruMultiplexing = if (profile is ProxyProfile.Mieru) profile.multiplexing else "middle",
                    mieruMtu = if (profile is ProxyProfile.Mieru) profile.mtu else 1400,
                    sshAuthType = if (profile is ProxyProfile.Ssh) profile.authType else RelaySshAuthTypePassword,
                    sshHostKeyFingerprint =
                        if (profile is ProxyProfile.Ssh) profile.hostKeyFingerprint.orEmpty() else "",
                    sshStrictHostKey = profile is ProxyProfile.Ssh && profile.strictHostKey,
                    udpEnabled = udpEnabled,
                )
            profileMutations.upsertRelay(
                profile = relayProfile,
                credentials = relayCredentials(profileId, profile),
                enabled = true,
                select = true,
                modeAfterImage = modeAfterImage,
                xraySelectionAfterImage = xraySelectionAfterImage,
            )
        }

        /** Relay-kind id for a relay-activatable [profile], or `null` for non-relay kinds. */
        private fun relayKindFor(profile: ProxyProfile): String? =
            when (profile) {
                is ProxyProfile.Trojan -> RelayKindTrojan
                is ProxyProfile.Shadowsocks -> RelayKindShadowsocks
                is ProxyProfile.AnyTls -> RelayKindAnyTls
                is ProxyProfile.VlessReality -> RelayKindVlessReality
                is ProxyProfile.Vless -> if (profile.hasXhttpTransport()) RelayKindVless else null
                is ProxyProfile.Hysteria2 -> RelayKindHysteria2
                is ProxyProfile.Ssh -> RelayKindSsh
                is ProxyProfile.Mieru -> RelayKindMieru
                else -> null
            }

        private fun ProxyProfile.hasXhttpTransport(): Boolean =
            when (this) {
                is ProxyProfile.Vless -> xhttpPath != null || xhttpHost != null
                is ProxyProfile.VlessReality -> xhttpPath != null || xhttpHost != null
                else -> false
            }

        private fun ProxyProfile.vlessFlow(): String =
            when (this) {
                is ProxyProfile.Vless -> flow
                is ProxyProfile.VlessReality -> flow
                else -> com.poyka.ripdpi.data.RelayVlessFlowVision
            }

        private fun ProxyProfile.vlessFingerprint(): String =
            when (this) {
                is ProxyProfile.Vless -> fingerprint.orEmpty()
                is ProxyProfile.VlessReality -> fingerprint.orEmpty()
                else -> ""
            }

        private fun ProxyProfile.vlessXhttpPath(): String =
            when (this) {
                is ProxyProfile.Vless -> xhttpPath.orEmpty()
                is ProxyProfile.VlessReality -> xhttpPath.orEmpty()
                else -> ""
            }

        private fun ProxyProfile.vlessXhttpHost(): String =
            when (this) {
                is ProxyProfile.Vless -> xhttpHost.orEmpty()
                is ProxyProfile.VlessReality -> xhttpHost.orEmpty()
                else -> ""
            }

        private fun ProxyProfile.vlessXhttpMode(): String =
            when (this) {
                is ProxyProfile.Vless -> xhttpMode
                is ProxyProfile.VlessReality -> xhttpMode
                else -> com.poyka.ripdpi.data.RelayXhttpModeAuto
            }

        private fun relayUdpEnabled(profile: ProxyProfile): Boolean =
            when (profile) {
                is ProxyProfile.Ssh,
                is ProxyProfile.Vless,
                is ProxyProfile.VlessReality,
                is ProxyProfile.Mieru,
                -> false

                else -> true
            }

        private fun relayEndpoint(profile: ProxyProfile): RelayActivationEndpoint =
            when (profile) {
                is ProxyProfile.Trojan -> {
                    RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
                }

                is ProxyProfile.Shadowsocks -> {
                    RelayActivationEndpoint(profile.server, profile.serverPort, profile.server)
                }

                is ProxyProfile.AnyTls -> {
                    RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName)
                }

                is ProxyProfile.VlessReality -> {
                    RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName)
                }

                is ProxyProfile.Vless -> {
                    RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
                }

                is ProxyProfile.Hysteria2 -> {
                    RelayActivationEndpoint(profile.server, profile.serverPort, profile.serverName ?: profile.server)
                }

                is ProxyProfile.Ssh -> {
                    RelayActivationEndpoint(profile.server, profile.serverPort, profile.server)
                }

                is ProxyProfile.Mieru -> {
                    RelayActivationEndpoint(profile.server, profile.serverPort, profile.server)
                }

                else -> {
                    error("unsupported relay activation profile ${profile::class.simpleName}")
                }
            }

        private fun relayCredentials(
            profileId: String,
            profile: ProxyProfile,
        ): RelayCredentialRecord =
            when (profile) {
                is ProxyProfile.Trojan -> {
                    RelayCredentialRecord(profileId = profileId, trojanPassword = profile.password)
                }

                is ProxyProfile.Shadowsocks -> {
                    RelayCredentialRecord(
                        profileId = profileId,
                        shadowsocksMethod = profile.method,
                        shadowsocksPassword = profile.password,
                    )
                }

                is ProxyProfile.AnyTls -> {
                    RelayCredentialRecord(profileId = profileId, anyTlsPassword = profile.password)
                }

                is ProxyProfile.VlessReality -> {
                    RelayCredentialRecord(profileId = profileId, vlessUuid = profile.uuid)
                }

                is ProxyProfile.Vless -> {
                    RelayCredentialRecord(profileId = profileId, vlessUuid = profile.uuid)
                }

                is ProxyProfile.Hysteria2 -> {
                    RelayCredentialRecord(
                        profileId = profileId,
                        hysteriaPassword = profile.password,
                        hysteriaSalamanderKey = profile.obfsPassword,
                        hysteriaInsecure = profile.insecure ?: false,
                    )
                }

                is ProxyProfile.Ssh -> {
                    // Persist only the auth-relevant secret so a profile carrying
                    // both never leaks the unused credential into the Keystore,
                    // regardless of which caller built it.
                    val isKey = profile.authType == RelaySshAuthTypePrivateKey
                    RelayCredentialRecord(
                        profileId = profileId,
                        sshUsername = profile.username,
                        sshPassword = profile.password?.takeIf { !isKey },
                        sshPrivateKey = profile.privateKey?.takeIf { isKey },
                        sshPrivateKeyPassphrase = profile.privateKeyPassphrase?.takeIf { isKey },
                    )
                }

                is ProxyProfile.Mieru -> {
                    RelayCredentialRecord(
                        profileId = profileId,
                        mieruUsername = profile.username,
                        mieruPassword = profile.password,
                    )
                }

                else -> {
                    error("unsupported relay activation profile ${profile::class.simpleName}")
                }
            }
    }

private data class RelayActivationEndpoint(
    val server: String,
    val serverPort: Int,
    val serverName: String,
)

private class DirectRelayProfileMutationCoordinator(
    private val profiles: RelayProfileStore,
    private val credentials: RelayCredentialStore,
    private val settings: AppSettingsRepository,
) : ProfileMutationCoordinator {
    override suspend fun recover() = Unit

    override suspend fun <T> readRecovered(block: suspend () -> T): T = block()

    override suspend fun runReset(block: suspend () -> Unit) = block()

    override suspend fun upsertRelay(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: com.poyka.ripdpi.proto.AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord?,
    ) {
        profiles.save(profile)
        this.credentials.save(credentials)
        check(xraySelectionAfterImage == null) { "Direct relay activation cannot update Xray provider selection" }
        if (settingsAfterImage != null) {
            settings.replace(settingsAfterImage)
        } else if (select || modeAfterImage != null) {
            settings.update {
                setRelayEnabled(enabled)
                setRelayKind(profile.kind)
                setRelayProfileId(profile.id)
                setRelayServer(profile.server)
                setRelayServerPort(profile.serverPort)
                setRelayServerName(profile.serverName)
                setRelayRealityPublicKey(profile.realityPublicKey)
                setRelayRealityShortId(profile.realityShortId)
                setRelayVlessTransport(profile.vlessTransport)
                setRelayXhttpPath(profile.xhttpPath)
                setRelayXhttpHost(profile.xhttpHost)
                setRelayXhttpMode(profile.xhttpMode)
                setRelayUdpEnabled(profile.udpEnabled)
                setRelayMieruProtocol(profile.mieruProtocol)
                setRelayMieruMultiplexing(profile.mieruMultiplexing)
                setRelayMieruMtu(profile.mieruMtu)
                setRelaySshAuthType(profile.sshAuthType)
                setRelaySshHostKeyFingerprint(profile.sshHostKeyFingerprint)
                setRelaySshStrictHostKey(profile.sshStrictHostKey)
                modeAfterImage?.let(::setRipdpiMode)
            }
        }
    }

    override suspend fun upsertAwg(
        profile: com.poyka.ripdpi.data.awg.AwgProfileEntity,
        secrets: com.poyka.ripdpi.data.awg.AwgSecrets,
    ) = unsupported()

    override suspend fun deleteAwg(profileId: String) = unsupported()

    override suspend fun upsertWarp(
        profile: com.poyka.ripdpi.data.WarpProfile,
        credentials: com.poyka.ripdpi.data.WarpCredentials,
        endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ) = unsupported()

    override suspend fun deleteWarp(
        profileId: String,
        clearActive: Boolean,
    ) = unsupported()

    override suspend fun deactivateWarp(profileId: String) = unsupported()

    override suspend fun replacePrivateBackup(
        data: com.poyka.ripdpi.data.backup.BackupPrivateDataV1,
        rollbackData: com.poyka.ripdpi.data.backup.BackupPrivateDataV1?,
    ) = unsupported()

    private fun unsupported(): Nothing = error("Only Relay mutations are supported")
}
