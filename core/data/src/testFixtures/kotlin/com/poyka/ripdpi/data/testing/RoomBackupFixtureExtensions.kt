package com.poyka.ripdpi.data.testing

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.backup.BackupExportUseCase
import com.poyka.ripdpi.data.backup.BackupPrivateDataStore
import com.poyka.ripdpi.data.backup.BackupRestoreUseCase
import com.poyka.ripdpi.data.backup.DefaultBackupPrivateDataStore

fun RoomProfileFixture.backupPrivateDataStore(): BackupPrivateDataStore =
    DefaultBackupPrivateDataStore(profileDatabase, coordinator)

fun RoomProfileFixture.backupExportUseCase(dispatchers: AppCoroutineDispatchers): BackupExportUseCase =
    BackupExportUseCase(profileDatabase, dispatchers)

fun RoomProfileFixture.backupRestoreUseCase(): BackupRestoreUseCase = BackupRestoreUseCase(coordinator)
