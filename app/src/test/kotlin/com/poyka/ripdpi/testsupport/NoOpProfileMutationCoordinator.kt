package com.poyka.ripdpi.testsupport

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
    override suspend fun recover() = Unit

    override suspend fun <T> readRecovered(block: suspend () -> T): T = block()

    override suspend fun runReset(block: suspend () -> Unit) = block()

    override suspend fun upsertAwg(
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ) = Unit

    override suspend fun deleteAwg(profileId: String) = Unit

    override suspend fun upsertRelay(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
    ) = Unit

    override suspend fun upsertWarp(
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ) = Unit

    override suspend fun deleteWarp(
        profileId: String,
        clearActive: Boolean,
    ) = Unit

    override suspend fun deactivateWarp(profileId: String) = Unit

    override suspend fun replacePrivateBackup(
        data: BackupPrivateDataV1,
        rollbackData: BackupPrivateDataV1?,
    ) = Unit
}
