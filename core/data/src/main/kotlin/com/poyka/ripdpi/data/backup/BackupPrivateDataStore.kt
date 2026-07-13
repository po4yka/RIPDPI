package com.poyka.ripdpi.data.backup

import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot/replace boundary for FULL-only profile families stored outside proxy groups. */
interface BackupPrivateDataStore {
    suspend fun snapshot(): BackupPrivateDataV1

    suspend fun replaceAll(data: BackupPrivateDataV1)

    companion object {
        /** Test/default seam for callers that exercise only the legacy group-backed section. */
        val Empty: BackupPrivateDataStore =
            object : BackupPrivateDataStore {
                override suspend fun snapshot(): BackupPrivateDataV1 = BackupPrivateDataV1()

                override suspend fun replaceAll(data: BackupPrivateDataV1) = Unit
            }
    }
}

/** Production implementation with preimage compensation around the heterogeneous stores. */
@Singleton
class DefaultBackupPrivateDataStore
    @Inject
    constructor(
        private val relayProfiles: RelayProfileStore,
        private val relayCredentials: RelayCredentialStore,
        private val warpProfiles: WarpProfileStore,
        private val warpCredentials: WarpCredentialStore,
        private val warpEndpoints: WarpEndpointStore,
        private val awgProfiles: AwgProfileDao,
        private val awgCredentials: AwgCredentialStore,
        private val xrayMetadata: XrayProfileMetadataStore,
        private val xraySecrets: XrayProfileSecretStore,
        private val xraySelection: XrayProviderSelectionStore,
    ) : BackupPrivateDataStore {
        override suspend fun snapshot(): BackupPrivateDataV1 = consistentBackupSnapshot { snapshotOnce() }

        private suspend fun snapshotOnce(): BackupPrivateDataV1 {
            val relayProfileSnapshot = relayProfiles.list()
            val warpProfileSnapshot = warpProfiles.loadAll()
            val awgProfileSnapshot = awgProfiles.allProfiles()
            val xrayMetadataSnapshot = xrayMetadata.list()
            return BackupPrivateDataV1(
                relayProfiles = relayProfileSnapshot,
                relayCredentials = relayProfileSnapshot.mapNotNull { relayCredentials.load(it.id) },
                warpProfiles = warpProfileSnapshot,
                warpCredentials =
                    warpCredentials
                        .loadAll()
                        .filter { credential -> warpProfileSnapshot.any { it.id == credential.profileId } },
                warpActiveProfileId = warpProfiles.activeProfileId(),
                awgProfiles =
                    awgProfileSnapshot.map { profile ->
                        AwgBackupProfile(
                            id = profile.id,
                            name = profile.name,
                            requestJson = profile.requestJson,
                            updatedAt = profile.updatedAt,
                            secrets = awgCredentials.load(profile.id),
                        )
                    },
                xrayMetadata = xrayMetadataSnapshot,
                xraySecrets = xrayMetadataSnapshot.mapNotNull { xraySecrets.load(it.profileId) },
                xraySelection = xraySelection.current(),
            )
        }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun replaceAll(data: BackupPrivateDataV1) {
            data.validate()
            val preimage = snapshot()
            try {
                applySnapshot(data)
            } catch (failure: Exception) {
                val rollbackFailure =
                    runCatching {
                        withContext(NonCancellable) { applySnapshot(preimage) }
                    }.exceptionOrNull()
                if (rollbackFailure != null) failure.addSuppressed(rollbackFailure)
                throw failure
            }
        }

        private suspend fun applySnapshot(data: BackupPrivateDataV1) {
            relayCredentials.clearAll()
            relayProfiles.clearAll()
            data.relayCredentials.forEach { relayCredentials.save(it) }
            data.relayProfiles.forEach { relayProfiles.save(it) }

            warpEndpoints.clearAll()
            warpCredentials.clearAll()
            warpProfiles.clearAll()
            data.warpCredentials.forEach { warpCredentials.save(it.profileId, it) }
            data.warpProfiles.forEach { warpProfiles.save(it) }
            warpProfiles.setActiveProfileId(data.warpActiveProfileId)

            awgCredentials.clearAll()
            awgProfiles.deleteAll()
            data.awgProfiles.forEach { profile ->
                profile.secrets?.let { awgCredentials.save(profile.id, it) }
                awgProfiles.upsertProfile(profile.toEntity())
            }

            xraySecrets.clearAll()
            xrayMetadata.clearAll()
            data.xraySecrets.forEach { xraySecrets.save(it) }
            data.xrayMetadata.forEach { xrayMetadata.save(it) }
            xraySelection.update(data.xraySelection)
        }

        private fun BackupPrivateDataV1.validate() = validateBackupPrivateData()
    }

internal suspend fun consistentBackupSnapshot(read: suspend () -> BackupPrivateDataV1): BackupPrivateDataV1 {
    var lastFailure: IllegalArgumentException? = null
    repeat(BackupSnapshotAttempts) {
        val snapshot = read()
        try {
            snapshot.validateBackupPrivateData()
            return snapshot
        } catch (failure: IllegalArgumentException) {
            lastFailure = failure
        }
    }
    throw IllegalStateException("Private backup stores changed during snapshot", lastFailure)
}

private fun BackupPrivateDataV1.validateBackupPrivateData() {
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
    require(xraySecrets.all { it.profileId in xrayIds }) { "Xray secret references missing metadata" }
    require(
        xraySelection.providerKind != XrayProviderSelectionRecord.ProviderKindXray ||
            xraySelection.activeProfileId in xrayIds,
    ) { "Selected Xray profile is missing" }
}

private const val BackupSnapshotAttempts = 3

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupPrivateDataStoreModule {
    @Binds
    @Singleton
    abstract fun bindBackupPrivateDataStore(store: DefaultBackupPrivateDataStore): BackupPrivateDataStore
}
