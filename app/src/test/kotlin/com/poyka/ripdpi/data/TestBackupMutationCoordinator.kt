package com.poyka.ripdpi.data

/** Unit boundary for tests whose private/group stores are supplied separately. */
class TestBackupMutationCoordinator : ProfileMutationCoordinator {
    private val authority = testPauseAuthority()

    override fun warpRuntimeRevision(profileId: String) = 0L

    override suspend fun recover() = Unit

    override suspend fun <T> readRecovered(block: suspend () -> T): T = block()

    override suspend fun captureMutation(origin: ProfileMutationOrigin) =
        ProfileMutationPreparation(origin, authority.reference())

    override suspend fun commitMutationIntent(preparation: ProfileMutationPreparation) =
        authority.invalidateForMutation(
            preparation.origin,
            java.util.UUID
                .randomUUID()
                .toString(),
            preparation.expectedPauseAuthority,
        )

    override suspend fun runReset(block: suspend (DurableCommandReceipt) -> Unit): DurableCommandReceipt {
        val receipt = authority.supersede(RuntimeUserCommand.Stop)
        block(receipt)
        return receipt
    }

    override suspend fun upsertAwg(
        preparation: ProfileMutationPreparation,
        profile: com.poyka.ripdpi.data.awg.AwgProfileEntity,
        secrets: com.poyka.ripdpi.data.awg.AwgSecrets,
    ): ProfileMutationOutcome = error("Not used by backup fixture")

    override suspend fun deleteAwg(
        preparation: ProfileMutationPreparation,
        profileId: String,
    ): ProfileMutationOutcome = error("Not used by backup fixture")

    override suspend fun upsertRelay(
        preparation: ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: com.poyka.ripdpi.proto.AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord?,
        expectedState: ExpectedRelayProfileState?,
    ): ProfileMutationOutcome = error("Not used by backup fixture")

    override suspend fun upsertWarp(
        preparation: ProfileMutationPreparation,
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ): ProfileMutationOutcome = error("Not used by backup fixture")

    override suspend fun upsertWarpForRuntimeProvisioning(
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
        expectedCredentials: WarpCredentials,
        expectedRevision: Long,
    ): Boolean = error("Not used by backup fixture")

    override suspend fun deleteWarp(
        preparation: ProfileMutationPreparation,
        profileId: String,
        clearActive: Boolean,
    ): ProfileMutationOutcome = error("Not used by backup fixture")

    override suspend fun deactivateWarp(
        preparation: ProfileMutationPreparation,
        profileId: String,
    ): ProfileMutationOutcome = error("Not used by backup fixture")

    override suspend fun replacePrivateBackup(
        preparation: ProfileMutationPreparation,
        data: com.poyka.ripdpi.data.backup.BackupPrivateDataV1,
        rollbackData: com.poyka.ripdpi.data.backup.BackupPrivateDataV1?,
    ): ProfileMutationOutcome = error("Private store fixture owns this operation")
}

fun testEmptyBackupPrivateDataStore(): com.poyka.ripdpi.data.backup.BackupPrivateDataStore =
    object : com.poyka.ripdpi.data.backup.BackupPrivateDataStore {
        override suspend fun snapshot() =
            com.poyka.ripdpi.data.backup
                .BackupPrivateDataV1()

        override suspend fun replaceAll(
            preparation: ProfileMutationPreparation,
            data: com.poyka.ripdpi.data.backup.BackupPrivateDataV1,
        ): ProfileMutationOutcome = testMutationOutcome(preparation.origin)
    }
