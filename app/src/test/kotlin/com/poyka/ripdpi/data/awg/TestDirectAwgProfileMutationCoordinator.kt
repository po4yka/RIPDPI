package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.rollbackStoreMutation

class TestDirectAwgProfileMutationCoordinator(
    private val dao: AwgProfileDao,
    private val credentials: AwgCredentialStore,
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
    ): com.poyka.ripdpi.data.ProfileMutationOutcome {
        val previousProfile = dao.getProfile(profile.id)
        val previousSecrets = credentials.load(profile.id)
        runCatching {
            credentials.save(profile.id, secrets)
            dao.upsertProfile(profile)
        }.exceptionOrNull()?.rollbackStoreMutation(
            {
                if (previousSecrets ==
                    null
                ) {
                    credentials.clear(profile.id)
                } else {
                    credentials.save(profile.id, previousSecrets)
                }
            },
            {
                if (previousProfile == null) {
                    dao.getProfile(profile.id)?.let { dao.deleteProfile(it) }
                } else {
                    dao.upsertProfile(previousProfile)
                }
            },
        )

        return com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)
    }

    override suspend fun deleteAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome {
        credentials.clear(profileId)
        dao.getProfile(profileId)?.let { dao.deleteProfile(it) }

        return com.poyka.ripdpi.data
            .testMutationOutcome(preparation.origin)
    }

    override suspend fun upsertRelay(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: com.poyka.ripdpi.data.RelayProfileRecord,
        credentials: com.poyka.ripdpi.data.RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: com.poyka.ripdpi.proto.AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord?,
        expectedState: com.poyka.ripdpi.data.ExpectedRelayProfileState?,
    ) = unsupported()

    override suspend fun upsertWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: com.poyka.ripdpi.data.WarpProfile,
        credentials: com.poyka.ripdpi.data.WarpCredentials,
        endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ) = unsupported()

    override suspend fun upsertWarpForRuntimeProvisioning(
        profile: com.poyka.ripdpi.data.WarpProfile,
        credentials: com.poyka.ripdpi.data.WarpCredentials,
        endpoints: List<com.poyka.ripdpi.data.WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
        expectedCredentials: com.poyka.ripdpi.data.WarpCredentials,
        expectedRevision: Long,
    ): Boolean = unsupported()

    override suspend fun deleteWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
        clearActive: Boolean,
    ) = unsupported()

    override suspend fun deactivateWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ) = unsupported()

    override suspend fun replacePrivateBackup(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        data: com.poyka.ripdpi.data.backup.BackupPrivateDataV1,
        rollbackData: com.poyka.ripdpi.data.backup.BackupPrivateDataV1?,
    ) = unsupported()

    private fun unsupported(): Nothing = error("Only AWG mutations are supported")
}
