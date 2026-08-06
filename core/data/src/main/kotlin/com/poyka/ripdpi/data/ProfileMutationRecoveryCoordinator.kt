package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiContractJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileMutationStores
    @Inject
    constructor(
        val settings: AppSettingsRepository,
        val relayProfiles: RelayProfileStore,
        val relayCredentials: RelayCredentialStore,
        val warpProfiles: WarpProfileStore,
        val warpCredentials: WarpCredentialStore,
        val warpEndpoints: WarpEndpointStore,
        val xrayMetadata: XrayProfileMetadataStore,
        val xraySecrets: XrayProfileSecretStore,
        val xraySelection: XrayProviderSelectionStore,
    )

interface ProfileMutationCoordinator {
    suspend fun recover()

    suspend fun runReset(block: suspend () -> Unit)

    suspend fun upsertAwg(
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    )

    suspend fun deleteAwg(profileId: String)

    suspend fun upsertRelay(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings? = null,
    )

    suspend fun upsertWarp(
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    )

    suspend fun deleteWarp(
        profileId: String,
        clearActive: Boolean,
    )

    suspend fun deactivateWarp(profileId: String)

    suspend fun replacePrivateBackup(
        data: BackupPrivateDataV1,
        rollbackData: BackupPrivateDataV1? = null,
    )
}

/** Replays encrypted after-images until every store in a profile mutation agrees. */
@Singleton
class ProfileMutationRecoveryCoordinator
    @Inject
    constructor(
        private val stores: ProfileMutationStores,
        private val awgProfiles: AwgProfileDao,
        private val awgCredentials: AwgCredentialStore,
        private val journal: ProfileMutationJournal,
    ) : ProfileMutationCoordinator {
        private val mutex = Mutex()
        private val json = RipDpiContractJson
        private val recoveryReader = ProfileMutationJournalRecoveryReader(journal)

        override suspend fun recover() = mutex.withLock { recoverLocked() }

        override suspend fun runReset(block: suspend () -> Unit) =
            mutex.withLock {
                journal.clearForReset()
                block()
            }

        override suspend fun upsertAwg(
            profile: AwgProfileEntity,
            secrets: AwgSecrets,
        ) = execute(
            AwgUpsertIntent(
                id = profile.id,
                name = profile.name,
                requestJson = profile.requestJson,
                updatedAt = profile.updatedAt,
                secrets = secrets,
            ),
        )

        override suspend fun deleteAwg(profileId: String) = execute(AwgDeleteIntent(profileId))

        override suspend fun upsertRelay(
            profile: RelayProfileRecord,
            credentials: RelayCredentialRecord,
            enabled: Boolean,
            select: Boolean,
            settingsAfterImage: AppSettings?,
        ) = execute(
            RelayUpsertIntent(
                profile = profile,
                credentials = credentials,
                enabled = enabled,
                select = select,
                settingsAfterImageBase64 = settingsAfterImage?.toByteArray()?.let(Base64.getEncoder()::encodeToString),
            ),
        )

        override suspend fun upsertWarp(
            profile: WarpProfile,
            credentials: WarpCredentials,
            endpoints: List<WarpEndpointCacheEntry>,
            activate: Boolean,
            scannerMode: String,
        ) = execute(WarpUpsertIntent(profile, credentials, endpoints, activate, scannerMode))

        override suspend fun deleteWarp(
            profileId: String,
            clearActive: Boolean,
        ) = execute(WarpDeleteIntent(profileId, clearActive))

        override suspend fun deactivateWarp(profileId: String) = execute(WarpDeactivateIntent(profileId))

        override suspend fun replacePrivateBackup(
            data: BackupPrivateDataV1,
            rollbackData: BackupPrivateDataV1?,
        ) {
            val target = PrivateBackupReplaceIntent(data)
            if (rollbackData == null) {
                execute(target)
            } else {
                executeWithCompensation(target, PrivateBackupReplaceIntent(rollbackData))
            }
        }

        private suspend fun execute(intent: ProfileMutationIntent) =
            mutex.withLock {
                recoverLocked()
                val pending = intent.toPendingMutation()
                journal.prepare(pending)
                replay(intent)
                journal.complete(pending.mutationId)
            }

        private suspend fun executeWithCompensation(
            target: ProfileMutationIntent,
            rollback: ProfileMutationIntent,
        ) = mutex.withLock {
            recoverLocked()
            val targetPending = target.toPendingMutation()
            journal.prepare(targetPending)
            val failure =
                runCatching {
                    replay(target)
                    journal.complete(targetPending.mutationId)
                }.exceptionOrNull()
            if (failure != null) {
                val rollbackFailure =
                    runCatching {
                        withContext(NonCancellable) {
                            val rollbackPending = rollback.toPendingMutation()
                            journal.replace(targetPending.mutationId, rollbackPending)
                            replay(rollback)
                            journal.complete(rollbackPending.mutationId)
                        }
                    }.exceptionOrNull()
                if (rollbackFailure != null && rollbackFailure !== failure) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }

        private fun ProfileMutationIntent.toPendingMutation() =
            PendingProfileMutation(
                family = family,
                payload = json.encodeToString(ProfileMutationIntent.serializer(), this),
            )

        private suspend fun recoverLocked() {
            recoveryReader.read()?.let { recovered ->
                replay(recovered.intent)
                journal.complete(recovered.pending.mutationId)
            }
        }

        private suspend fun replay(intent: ProfileMutationIntent) {
            when (intent) {
                is AwgUpsertIntent -> {
                    awgCredentials.save(intent.id, intent.secrets)
                    awgProfiles.upsertProfile(intent.toEntity())
                }

                is AwgDeleteIntent -> {
                    awgCredentials.clear(intent.profileId)
                    awgProfiles.getProfile(intent.profileId)?.let { awgProfiles.deleteProfile(it) }
                }

                is RelayUpsertIntent -> {
                    stores.relayProfiles.save(intent.profile)
                    stores.relayCredentials.save(intent.credentials)
                    if (intent.settingsAfterImageBase64 != null) {
                        stores.settings.replace(
                            AppSettings.parseFrom(Base64.getDecoder().decode(intent.settingsAfterImageBase64)),
                        )
                    } else if (intent.select) {
                        stores.settings.update { applyRelayAfterImage(intent.profile, intent.enabled) }
                    }
                }

                is WarpUpsertIntent -> {
                    replayWarpUpsert(intent)
                }

                is WarpDeleteIntent -> {
                    replayWarpDelete(intent)
                }

                is WarpDeactivateIntent -> {
                    replayWarpDeactivate(intent)
                }

                is PrivateBackupReplaceIntent -> {
                    replayPrivateBackup(intent.data)
                }
            }
        }

        private suspend fun replayPrivateBackup(data: BackupPrivateDataV1) {
            stores.warpProfiles.setActiveProfileId(null)
            stores.xraySelection.update(XrayProviderSelectionRecord())

            stores.relayCredentials.clearAll()
            stores.relayProfiles.clearAll()
            data.relayCredentials.forEach { stores.relayCredentials.save(it) }
            data.relayProfiles.forEach { stores.relayProfiles.save(it) }

            stores.warpEndpoints.clearAll()
            stores.warpCredentials.clearAll()
            stores.warpProfiles.clearAll()
            data.warpCredentials.forEach { stores.warpCredentials.save(it.profileId, it) }
            data.warpProfiles.forEach { stores.warpProfiles.save(it) }
            stores.warpProfiles.setActiveProfileId(data.warpActiveProfileId)

            awgCredentials.clearAll()
            awgProfiles.deleteAll()
            data.awgProfiles.forEach { profile ->
                profile.secrets?.let { awgCredentials.save(profile.id, it) }
                awgProfiles.upsertProfile(profile.toEntity())
            }

            stores.xraySecrets.clearAll()
            stores.xrayMetadata.clearAll()
            data.xraySecrets.forEach { stores.xraySecrets.save(it) }
            data.xrayMetadata.forEach { stores.xrayMetadata.save(it) }
            stores.xraySelection.update(data.xraySelection)
        }

        private suspend fun replayWarpUpsert(intent: WarpUpsertIntent) {
            stores.warpProfiles.save(intent.profile)
            stores.warpCredentials.save(intent.profile.id, intent.credentials)
            stores.warpEndpoints.clearProfile(intent.profile.id)
            intent.endpoints.forEach { stores.warpEndpoints.save(it) }
            if (intent.activate) {
                stores.warpProfiles.setActiveProfileId(intent.profile.id)
                stores.settings.update { applyWarpAfterImage(intent.profile, intent.scannerMode) }
            }
        }

        private suspend fun replayWarpDelete(intent: WarpDeleteIntent) {
            if (intent.clearActive) {
                stores.warpProfiles.setActiveProfileId(null)
                stores.settings.update { clearWarpAfterImage() }
            }
            stores.warpEndpoints.clearProfile(intent.profileId)
            stores.warpCredentials.clear(intent.profileId)
            stores.warpProfiles.remove(intent.profileId)
        }

        private suspend fun replayWarpDeactivate(intent: WarpDeactivateIntent) {
            if (stores.warpProfiles.activeProfileId() == intent.profileId ||
                stores.settings.snapshot().warpProfileId == intent.profileId
            ) {
                stores.warpProfiles.setActiveProfileId(null)
                stores.settings.update { clearWarpAfterImage() }
            }
        }
    }

internal const val ProfileMutationIntentSchemaVersion = 1

@Serializable
internal sealed interface ProfileMutationIntent {
    val family: ProfileMutationFamily
}

@Serializable
@SerialName("awg_upsert")
private data class AwgUpsertIntent(
    val id: String,
    val name: String,
    val requestJson: String,
    val updatedAt: Long,
    val secrets: AwgSecrets,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Awg

    fun toEntity() = AwgProfileEntity(id = id, name = name, requestJson = requestJson, updatedAt = updatedAt)
}

@Serializable
@SerialName("awg_delete")
private data class AwgDeleteIntent(
    val profileId: String,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Awg
}

@Serializable
@SerialName("relay_upsert")
private data class RelayUpsertIntent(
    val profile: RelayProfileRecord,
    val credentials: RelayCredentialRecord,
    val enabled: Boolean,
    val select: Boolean,
    val settingsAfterImageBase64: String? = null,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Relay
}

@Serializable
@SerialName("private_backup_replace")
private data class PrivateBackupReplaceIntent(
    val data: BackupPrivateDataV1,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Backup
}

@Serializable
@SerialName("warp_upsert")
private data class WarpUpsertIntent(
    val profile: WarpProfile,
    val credentials: WarpCredentials,
    val endpoints: List<WarpEndpointCacheEntry>,
    val activate: Boolean,
    val scannerMode: String,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Warp
}

@Serializable
@SerialName("warp_delete")
private data class WarpDeleteIntent(
    val profileId: String,
    val clearActive: Boolean,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Warp
}

@Serializable
@SerialName("warp_deactivate")
private data class WarpDeactivateIntent(
    val profileId: String,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Warp
}

private fun AppSettings.Builder.applyWarpAfterImage(
    profile: WarpProfile,
    scannerMode: String,
) {
    setWarpProfileId(profile.id)
    setWarpAccountKind(normalizeWarpAccountKind(profile.accountKind))
    setWarpZeroTrustOrg(profile.zeroTrustOrg)
    setWarpSetupState(normalizeWarpSetupState(profile.setupState))
    setWarpLastScannerMode(normalizeWarpScannerMode(scannerMode))
}

private fun AppSettings.Builder.clearWarpAfterImage() {
    setWarpProfileId(DefaultWarpProfileId)
    setWarpAccountKind(WarpAccountKindConsumerFree)
    setWarpZeroTrustOrg("")
    setWarpSetupState(WarpSetupStateNotConfigured)
    setWarpLastScannerMode(WarpScannerModeAutomatic)
}

private fun AppSettings.Builder.applyRelayAfterImage(
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
}
