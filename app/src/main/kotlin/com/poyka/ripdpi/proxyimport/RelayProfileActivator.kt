package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
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
        ): Boolean {
            val projection = RelayProfileProjection.from(profile, profileId, tlsFingerprintOverride)
            return if (projection == null) {
                false
            } else {
                profileMutations.upsertRelay(
                    profile = projection.profile,
                    credentials = projection.credentials,
                    enabled = true,
                    select = true,
                )
                true
            }
        }
    }

private class DirectRelayProfileMutationCoordinator(
    private val profiles: RelayProfileStore,
    private val credentials: RelayCredentialStore,
    private val settings: AppSettingsRepository,
) : ProfileMutationCoordinator {
    override suspend fun recover() = Unit

    override suspend fun runReset(block: suspend () -> Unit) = block()

    override suspend fun upsertRelay(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: com.poyka.ripdpi.proto.AppSettings?,
    ) {
        profiles.save(profile)
        this.credentials.save(credentials)
        if (settingsAfterImage != null) {
            settings.replace(settingsAfterImage)
        } else if (select) {
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
