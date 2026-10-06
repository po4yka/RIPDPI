package com.poyka.ripdpi.testsupport

import com.poyka.ripdpi.data.ExpectedRelayProfileState
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.proto.AppSettings

object NoOpProfileMutationCoordinator : ProfileMutationCoordinator {
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

    override fun warpRuntimeRevision(profileId: String): Long = 0L

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

    override suspend fun upsertAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)

    override suspend fun deleteAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)

    override suspend fun upsertRelay(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
        expectedState: ExpectedRelayProfileState?,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)

    override suspend fun upsertWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)

    override suspend fun upsertWarpForRuntimeProvisioning(
        profile: com.poyka.ripdpi.data.WarpProfile,
        credentials: com.poyka.ripdpi.data.WarpCredentials,
        endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
        expectedCredentials: com.poyka.ripdpi.data.WarpCredentials,
        expectedRevision: Long,
    ): Boolean = error("Unexpected runtime provisioning in no-op test coordinator")

    override suspend fun deleteWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
        clearActive: Boolean,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)

    override suspend fun deactivateWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)

    override suspend fun replacePrivateBackup(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        data: BackupPrivateDataV1,
        rollbackData: BackupPrivateDataV1?,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome =
        com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)
}
