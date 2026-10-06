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
    override suspend fun commitMutationIntent(preparation: com.poyka.ripdpi.data.ProfileMutationPreparation) =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)

    override suspend fun captureMutation(origin: com.poyka.ripdpi.data.ProfileMutationOrigin) =
        com.poyka.ripdpi.data
            .ProfileMutationPreparation(
                origin,
                com.poyka.ripdpi.data
                    .testPauseAuthority()
                    .reference(),
            )

    private var mutationRevision = 0L

    override fun warpRuntimeRevision(profileId: String): Long = mutationRevision

    private val stagedPreimages = mutableMapOf<String, WarpPreimage>()

    override suspend fun recover() = Unit

    override suspend fun <T> readRecovered(block: suspend () -> T): T = block()

    override suspend fun runReset(
        block: suspend (com.poyka.ripdpi.data.DurableCommandReceipt) -> Unit,
    ): com.poyka.ripdpi.data.DurableCommandReceipt {
        val receipt =
            com.poyka.ripdpi.data.testPauseAuthority().supersede(
                com.poyka.ripdpi.data.RuntimeUserCommand.Stop,
            )
        block(receipt)
        return receipt
    }

    override suspend fun upsertWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome {
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
        mutationRevision += 1L

        return com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)
    }

    override suspend fun upsertWarpForRuntimeProvisioning(
        profile: com.poyka.ripdpi.data.WarpProfile,
        credentials: com.poyka.ripdpi.data.WarpCredentials,
        endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
        expectedCredentials: com.poyka.ripdpi.data.WarpCredentials,
        expectedRevision: Long,
    ): Boolean {
        if (mutationRevision != expectedRevision || this.credentials.load(profile.id) != expectedCredentials ||
            settings.snapshot().warpProfileId != profile.id
        ) {
            return false
        }
        upsertWarp(
            captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.InternalReconcile),
            profile,
            credentials,
            endpoints,
            activate,
            scannerMode,
        )
        return true
    }

    override suspend fun deleteWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
        clearActive: Boolean,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome {
        if (clearActive) deactivateWarp(preparation, profileId)
        endpoints.clearProfile(profileId)
        credentials.clear(profileId)
        profiles.remove(profileId)

        return com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)
    }

    override suspend fun deactivateWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome {
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

        return com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)
    }

    override suspend fun upsertAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ) = unsupported()

    override suspend fun deleteAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ) = unsupported()

    override suspend fun upsertRelay(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
        expectedState: com.poyka.ripdpi.data.ExpectedRelayProfileState?,
    ) = unsupported()

    override suspend fun replacePrivateBackup(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
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
