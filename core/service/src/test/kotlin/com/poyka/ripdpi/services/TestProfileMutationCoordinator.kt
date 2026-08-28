package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.WarpAccountKindConsumerFree
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpSetupStateNotConfigured
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.rollbackStoreMutation
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.proto.AppSettings

internal class TestProfileMutationCoordinator(
    private val settings: AppSettingsRepository,
    private val profiles: WarpProfileStore,
    private val credentials: WarpCredentialStore,
    private val endpoints: WarpEndpointStore,
) : ProfileMutationCoordinator {
    private val stagedPreimages = mutableMapOf<String, WarpPreimage>()

    override suspend fun recover() = Unit

    override suspend fun <T> readRecovered(block: suspend () -> T): T = block()

    override suspend fun runReset(block: suspend () -> Unit) = block()

    override suspend fun upsertWarp(
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ) {
        val preimage = stagedPreimages.remove(profile.id) ?: capturePreimage(profile.id)
        if (!activate) stagedPreimages[profile.id] = preimage
        runCatching {
            profiles.save(profile)
            this.credentials.save(profile.id, credentials)
            this.endpoints.clearProfile(profile.id)
            endpoints.forEach { this.endpoints.save(it) }
            if (activate) {
                profiles.setActiveProfileId(profile.id)
                settings.update {
                    setWarpProfileId(profile.id)
                    setWarpAccountKind(profile.accountKind)
                    setWarpZeroTrustOrg(profile.zeroTrustOrg)
                    setWarpSetupState(profile.setupState)
                    setWarpLastScannerMode(scannerMode)
                }
            }
        }.exceptionOrNull()?.rollbackStoreMutation(
            { restoreProfile(profile.id, preimage) },
            { restoreCredentials(profile.id, preimage) },
            { restoreEndpoints(profile.id, preimage) },
            { profiles.setActiveProfileId(preimage.activeProfileId) },
            { settings.replace(preimage.settings) },
        )
    }

    override suspend fun deleteWarp(
        profileId: String,
        clearActive: Boolean,
    ) {
        if (clearActive) deactivateWarp(profileId)
        endpoints.clearProfile(profileId)
        credentials.clear(profileId)
        profiles.remove(profileId)
    }

    override suspend fun deactivateWarp(profileId: String) {
        if (profiles.activeProfileId() == profileId || settings.snapshot().warpProfileId == profileId) {
            profiles.setActiveProfileId(null)
            settings.update {
                setWarpProfileId(DefaultWarpProfileId)
                setWarpAccountKind(WarpAccountKindConsumerFree)
                setWarpZeroTrustOrg("")
                setWarpSetupState(WarpSetupStateNotConfigured)
                setWarpLastScannerMode(WarpScannerModeAutomatic)
            }
        }
    }

    override suspend fun upsertAwg(
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ) = unsupported()

    override suspend fun deleteAwg(profileId: String) = unsupported()

    override suspend fun upsertRelay(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
    ) = unsupported()

    override suspend fun replacePrivateBackup(
        data: BackupPrivateDataV1,
        rollbackData: BackupPrivateDataV1?,
    ) = unsupported()

    private suspend fun capturePreimage(profileId: String) =
        WarpPreimage(
            profile = profiles.load(profileId),
            credentials = credentials.load(profileId),
            endpoints = endpoints.loadAll(profileId),
            activeProfileId = profiles.activeProfileId(),
            settings = settings.snapshot(),
        )

    private suspend fun restoreProfile(
        profileId: String,
        preimage: WarpPreimage,
    ) {
        if (preimage.profile == null) profiles.remove(profileId) else profiles.save(preimage.profile)
    }

    private suspend fun restoreCredentials(
        profileId: String,
        preimage: WarpPreimage,
    ) {
        if (preimage.credentials ==
            null
        ) {
            credentials.clear(profileId)
        } else {
            credentials.save(profileId, preimage.credentials)
        }
    }

    private suspend fun restoreEndpoints(
        profileId: String,
        preimage: WarpPreimage,
    ) {
        endpoints.clearProfile(profileId)
        preimage.endpoints.forEach { endpoints.save(it) }
    }

    private fun unsupported(): Nothing = error("Unsupported test mutation family")
}

private data class WarpPreimage(
    val profile: WarpProfile?,
    val credentials: WarpCredentials?,
    val endpoints: List<WarpEndpointCacheEntry>,
    val activeProfileId: String?,
    val settings: AppSettings,
)
