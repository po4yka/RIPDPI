package com.poyka.ripdpi.data.awg

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.rollbackStoreMutation

class TestDirectAwgProfileMutationCoordinator(
    private val dao: AwgProfileDao,
    private val credentials: AwgCredentialStore,
) : ProfileMutationCoordinator {
    override suspend fun activateStandaloneAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome = error("Standalone activation is outside this test boundary")

    override suspend fun compensateStandaloneAwg(
        receipt: com.poyka.ripdpi.data.ProfileActivationReceipt,
        expectedProfileId: String,
    ): Boolean = error("Standalone compensation is outside this test boundary")

    override suspend fun clearStandaloneAwg(
        receipt: com.poyka.ripdpi.data.RuntimeStopReceipt,
        expectedProfileId: String,
    ): Boolean = error("Standalone deactivation is outside this test boundary")

    override suspend fun <T> mutateCatalog(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        block: suspend () -> T,
    ): T {
        check(commitMutationIntent(preparation) != com.poyka.ripdpi.data.ProfileMutationOutcome.Superseded)
        return block()
    }

    override suspend fun <T> mutateReservedCatalog(
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
        block: suspend () -> T,
    ): T = block()

    override suspend fun activateSelector(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        groupId: String,
        memberId: String,
        choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
    ): com.poyka.ripdpi.data.ProfileMutationOutcome {
        val outcome = commitMutationIntent(preparation)
        val receipt =
            (outcome as? com.poyka.ripdpi.data.ProfileMutationOutcome.Reserved)?.receipt
                as? com.poyka.ripdpi.data.ProfileActivationReceipt
        if (receipt != null) {
            choice.commitMember(
                groupId,
                memberId,
                com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                    .Manual(receipt),
            )
        }
        return outcome
    }

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
            com.poyka.ripdpi.data
                .testPauseAuthority()
                .reserveStop()
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
