package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.backup.validateBackupPrivateData
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.sync.withLock

interface ProfileBackupMutationCoordinator {
    suspend fun restoreBackup(
        preparation: ProfileMutationPreparation,
        groups: List<ProxyGroup>?,
        rules: List<com.poyka.ripdpi.data.rules.RuleEntity>?,
        settings: AppSettings?,
        privateData: BackupPrivateDataV1?,
    ): ProfileMutationOutcome

    suspend fun replacePrivateBackup(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        data: BackupPrivateDataV1,
    ): ProfileMutationOutcome
}

/** Commits a backup replacement with its profile ownership and settings in one transaction. */
internal class ProfileBackupMutations(
    private val runtime: ProfileMutationRuntime,
) : ProfileBackupMutationCoordinator {
    override suspend fun replacePrivateBackup(
        preparation: ProfileMutationPreparation,
        data: BackupPrivateDataV1,
    ): ProfileMutationOutcome {
        data.validateBackupPrivateData()
        return runtime.afterImageWriter.execute(preparation, PrivateBackupReplaceIntent(data, null))
    }

    override suspend fun restoreBackup(
        preparation: ProfileMutationPreparation,
        groups: List<ProxyGroup>?,
        rules: List<com.poyka.ripdpi.data.rules.RuleEntity>?,
        settings: AppSettings?,
        privateData: BackupPrivateDataV1?,
    ): ProfileMutationOutcome =
        runtime.mutex.withLock {
            privateData?.validateBackupPrivateData()
            runtime.recover()
            runtime.catalog.invalidate()
            runtime.database.withAuthority(
                runtime.pauseAuthority,
                afterCommit = {
                    runtime.warpRevisions.invalidateAll()
                    runtime.mutationGeneration.completed()
                },
            ) { rows ->
                val result =
                    runtime.pauseAuthority.invalidateForMutation(
                        preparation.origin,
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                        preparation.expectedPauseAuthority,
                    )
                check(result != ProfileMutationOutcome.Superseded) { "Profile restore was superseded" }
                privateData?.let {
                    ProfileMutationRowWriter(
                        rows,
                        runtime.pauseAuthority,
                    ).apply(PrivateBackupReplaceIntent(it, null))
                }
                groups?.let { rows.groups.replace(it.map(ProxyGroup::withNewOwnershipGeneration)) }
                rules?.let {
                    rows.rules.clear()
                    rows.rules.insert(it)
                }
                settings?.let(rows.settings::replace)
                result
            }
        }
}
