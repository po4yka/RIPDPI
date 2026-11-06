package com.poyka.ripdpi.data.backup

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.UnreadableCredentialException
import com.poyka.ripdpi.data.logUnreadableCredential
import com.poyka.ripdpi.data.persistence.ProfileDatabase
import com.poyka.ripdpi.data.persistence.ProfileRows
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot/replace boundary for FULL-only profile families stored outside proxy groups. */
interface BackupPrivateDataStore {
    suspend fun snapshot(): BackupPrivateDataV1

    suspend fun replaceAll(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        data: BackupPrivateDataV1,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome
}

/** Private profile backup reads and replacements use the shared Room transaction boundary. */
@Singleton
class DefaultBackupPrivateDataStore
    @Inject
    internal constructor(
        private val database: ProfileDatabase,
        private val profileMutations: ProfileMutationCoordinator,
    ) : BackupPrivateDataStore {
        override suspend fun snapshot(): BackupPrivateDataV1 = database.read { it.privateBackupSnapshot() }

        override suspend fun replaceAll(
            preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
            data: BackupPrivateDataV1,
        ): com.poyka.ripdpi.data.ProfileMutationOutcome {
            data.validateBackupPrivateData()
            return profileMutations.replacePrivateBackup(preparation, data)
        }
    }

internal fun ProfileRows.privateBackupSnapshot(): BackupPrivateDataV1 {
    val relayProfileSnapshot = relayProfiles.list()
    val warpProfileSnapshot = warpProfiles.loadAll()
    val awgProfileSnapshot = awgProfiles.allProfiles()
    val unreadableXrayIds = mutableSetOf<String>()
    val xrayPairs = mutableListOf<Pair<XrayProfileMetadataRecord, XrayProfileSecretRecord?>>()
    for (metadata in xrayMetadata.list()) {
        try {
            xrayPairs += metadata to xraySecrets.load(metadata.profileId)
        } catch (error: UnreadableCredentialException) {
            logUnreadableCredential(UnreadableBackupAction, error)
            unreadableXrayIds += metadata.profileId
        }
    }
    return BackupPrivateDataV1(
        relayProfiles = relayProfileSnapshot,
        relayCredentials = relayProfileSnapshot.mapNotNull { exportable { relayCredentials.load(it.id) } },
        warpProfiles = warpProfileSnapshot,
        warpCredentials =
            warpCredentials.loadAll().filter { credential ->
                warpProfileSnapshot.any {
                    it.id ==
                        credential.profileId
                }
            },
        warpActiveProfileId = warpProfiles.activeProfileId(),
        awgProfiles =
            awgProfileSnapshot.map { profile ->
                AwgBackupProfile(
                    id = profile.id,
                    name = profile.name,
                    requestJson = profile.requestJson,
                    updatedAt = profile.updatedAt,
                    secrets = exportable { awgCredentials.load(profile.id) },
                )
            },
        xrayMetadata = xrayPairs.map { it.first },
        xraySecrets = xrayPairs.mapNotNull { it.second },
        xraySelection =
            selection.xray().takeUnless { it.activeProfileId in unreadableXrayIds }
                ?: XrayProviderSelectionRecord(),
    ).also { it.validateBackupPrivateData() }
}

private fun <T : Any> exportable(load: () -> T?): T? =
    try {
        load()
    } catch (error: UnreadableCredentialException) {
        logUnreadableCredential(UnreadableBackupAction, error)
        null
    }

private const val UnreadableBackupAction = "left out of a backup"

internal fun BackupPrivateDataV1.validateBackupPrivateData() {
    val relayIds = relayProfiles.mapTo(mutableSetOf()) { it.id }
    require(relayIds.size == relayProfiles.size) { "Duplicate Relay profile id" }
    require(relayCredentials.all { it.profileId in relayIds }) {
        "Relay credential references a missing profile"
    }

    val warpIds = warpProfiles.mapTo(mutableSetOf()) { it.id }
    require(warpIds.size == warpProfiles.size) { "Duplicate WARP profile id" }
    require(warpCredentials.all { it.profileId in warpIds }) { "WARP credential references a missing profile" }
    require(warpActiveProfileId == null || warpActiveProfileId in warpIds) { "Active WARP profile is missing" }

    val awgIds = awgProfiles.mapTo(mutableSetOf()) { it.id }
    require(awgIds.size == awgProfiles.size) { "Duplicate AWG profile id" }

    val xrayIds = xrayMetadata.mapTo(mutableSetOf()) { it.profileId }
    require(xrayIds.size == xrayMetadata.size) { "Duplicate Xray profile id" }
    val secretsById = xraySecrets.associateBy { it.profileId }
    require(secretsById.size == xraySecrets.size) { "Duplicate Xray secret id" }
    require(secretsById.keys == xrayIds) { "Xray profile pair is incomplete" }
    require(xrayMetadata.all { it.revision.isNotBlank() && it.revision == secretsById[it.profileId]?.revision }) {
        "Xray profile revisions do not match"
    }
    require(
        xraySelection.providerKind in
            setOf(XrayProviderSelectionRecord.ProviderKindNative, XrayProviderSelectionRecord.ProviderKindXray),
    ) { "Unknown selected VPN provider" }
    require(
        xraySelection.providerKind != XrayProviderSelectionRecord.ProviderKindXray ||
            xraySelection.activeProfileId in xrayIds,
    ) { "Selected Xray profile is missing" }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupPrivateDataStoreModule {
    @Binds
    @Singleton
    abstract fun bindBackupPrivateDataStore(store: DefaultBackupPrivateDataStore): BackupPrivateDataStore
}
