package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileRecordPair
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.data.xray.toXrayProfileRecordPair
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiContractJson
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
        val bootSession: BootSessionStateStore,
    )

interface ProfileMutationCoordinator {
    suspend fun recover()

    suspend fun <T> readRecovered(block: suspend () -> T): T

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
        modeAfterImage: String? = null,
        xraySelectionAfterImage: XrayProviderSelectionRecord? = null,
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
    ) : ProfileMutationCoordinator,
        XrayProviderMutationCoordinator {
        private val mutex = Mutex()
        private val json = RipDpiContractJson
        private val recoveryReader = ProfileMutationJournalRecoveryReader(journal)
        private val replayWriter = ProfileMutationReplayWriter(stores, awgProfiles, awgCredentials)

        override suspend fun recover() = mutex.withLock { recoverLocked() }

        override suspend fun <T> readRecovered(block: suspend () -> T): T =
            mutex.withLock {
                recoverLocked()
                block()
            }

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
            modeAfterImage: String?,
            xraySelectionAfterImage: XrayProviderSelectionRecord?,
        ) = execute(
            RelayUpsertIntent(
                profile = profile,
                credentials = credentials,
                enabled = enabled,
                select = select,
                settingsAfterImageBase64 = settingsAfterImage?.toByteArray()?.let(Base64.getEncoder()::encodeToString),
                modeAfterImage = modeAfterImage,
                xraySelectionAfterImage = xraySelectionAfterImage,
            ),
        )

        override suspend fun upsertXrayProvider(
            profileId: String,
            profile: XrayProfile,
            selection: XrayProviderSelectionRecord,
            modeAfterImage: String,
        ) = execute(
            XrayProviderSelectIntent(
                records = profile.toXrayProfileRecordPair(profileId),
                selection = selection,
                modeAfterImage = modeAfterImage,
            ),
        )

        override suspend fun selectNativeProvider(
            selection: XrayProviderSelectionRecord,
            modeAfterImage: String,
        ) = execute(
            XrayProviderSelectIntent(
                records = null,
                selection = selection,
                modeAfterImage = modeAfterImage,
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
            val target = PrivateBackupReplaceIntent(data = data, activeAwgProfileId = null)
            if (rollbackData == null) {
                execute(target)
            } else {
                val rollbackAwgProfileId =
                    stores.bootSession
                        .activeAwgProfileId()
                        ?.takeIf { profileId -> rollbackData.awgProfiles.any { it.id == profileId } }
                executeWithCompensation(
                    target,
                    PrivateBackupReplaceIntent(
                        data = rollbackData,
                        activeAwgProfileId = rollbackAwgProfileId,
                    ),
                )
            }
        }

        private suspend fun execute(intent: ProfileMutationIntent) =
            mutex.withLock {
                recoverLocked()
                val pending = intent.toPendingMutation()
                journal.prepare(pending)
                replayWriter.replay(intent)
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
                    replayWriter.replay(target)
                    journal.complete(targetPending.mutationId)
                }.exceptionOrNull()
            if (failure != null) {
                val rollbackFailure =
                    runCatching {
                        withContext(NonCancellable) {
                            val rollbackPending = rollback.toPendingMutation()
                            journal.replace(targetPending.mutationId, rollbackPending)
                            replayWriter.replay(rollback)
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
                replayWriter.replay(recovered.intent)
                journal.complete(recovered.pending.mutationId)
            }
        }
    }

private class ProfileMutationReplayWriter(
    private val stores: ProfileMutationStores,
    private val awgProfiles: AwgProfileDao,
    private val awgCredentials: AwgCredentialStore,
) {
    suspend fun replay(intent: ProfileMutationIntent) {
        when (intent) {
            is AwgUpsertIntent -> replayAwgUpsert(intent)
            is AwgDeleteIntent -> replayAwgDelete(intent)
            is RelayUpsertIntent -> replayRelayUpsert(intent)
            is WarpUpsertIntent -> replayWarpUpsert(intent)
            is WarpDeleteIntent -> replayWarpDelete(intent)
            is WarpDeactivateIntent -> replayWarpDeactivate(intent)
            is PrivateBackupReplaceIntent -> replayPrivateBackup(intent)
            is XrayProviderSelectIntent -> replayXrayProviderSelect(intent)
        }
    }

    private suspend fun replayAwgUpsert(intent: AwgUpsertIntent) {
        awgCredentials.save(intent.id, intent.secrets)
        awgProfiles.upsertProfile(intent.toEntity())
    }

    private suspend fun replayAwgDelete(intent: AwgDeleteIntent) {
        if (stores.bootSession.activeAwgProfileId() == intent.profileId) {
            stores.bootSession.setActiveAwgProfileId(null)
        }
        awgCredentials.clear(intent.profileId)
        awgProfiles.getProfile(intent.profileId)?.let { awgProfiles.deleteProfile(it) }
    }

    private suspend fun replayRelayUpsert(intent: RelayUpsertIntent) {
        stores.relayProfiles.save(intent.profile)
        stores.relayCredentials.save(intent.credentials)
        intent.xraySelectionAfterImage?.let(stores.xraySelection::update)
        if (intent.settingsAfterImageBase64 != null) {
            stores.settings.replace(
                AppSettings.parseFrom(Base64.getDecoder().decode(intent.settingsAfterImageBase64)),
            )
        } else if (intent.select || intent.modeAfterImage != null) {
            stores.settings.update {
                if (intent.select) {
                    applyRelayAfterImage(intent.profile, intent.enabled)
                }
                intent.modeAfterImage?.let(::setRipdpiMode)
            }
        }
    }

    private suspend fun replayXrayProviderSelect(intent: XrayProviderSelectIntent) {
        intent.records?.let { records ->
            stores.xraySecrets.save(records.secret)
            stores.xrayMetadata.save(records.metadata)
        }
        stores.xraySelection.update(intent.selection)
        stores.settings.update { setRipdpiMode(intent.modeAfterImage) }
    }

    private suspend fun replayPrivateBackup(intent: PrivateBackupReplaceIntent) {
        val data = intent.data
        stores.bootSession.setActiveAwgProfileId(null)
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

        intent.activeAwgProfileId
            ?.takeIf { profileId -> data.awgProfiles.any { it.id == profileId } }
            ?.let(stores.bootSession::setActiveAwgProfileId)
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
    val modeAfterImage: String? = null,
    val xraySelectionAfterImage: XrayProviderSelectionRecord? = null,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Relay
}

@Serializable
@SerialName("xray_provider_select")
private data class XrayProviderSelectIntent(
    val records: XrayProfileRecordPair? = null,
    val selection: XrayProviderSelectionRecord,
    val modeAfterImage: String,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Xray
}

@Serializable
@SerialName("private_backup_replace")
private data class PrivateBackupReplaceIntent(
    val data: BackupPrivateDataV1,
    val activeAwgProfileId: String? = null,
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
