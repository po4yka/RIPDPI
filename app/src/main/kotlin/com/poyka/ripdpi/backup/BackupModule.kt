package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.backup.BackupProfileProvider
import com.poyka.ripdpi.data.backup.BackupProfileRestoreSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the backup export/restore feature.
 *
 * [BackupProfileProvider] / [BackupProfileRestoreSink] currently resolve to no-ops:
 * proxy profiles are persisted as per-id encrypted blobs in `:core:service`
 * ([com.poyka.ripdpi.keystore.EncryptedProfileStore]) with no enumeration API, so
 * there is no single live `List<ProxyProfile>` to gather or replace yet. Groups,
 * routing rules, and settings ARE exported and restored.
 *
 * The follow-up profile-enumeration task MUST replace BOTH bindings together with a
 * provider/sink backed by a real enumeration source once one exists: the export
 * gather use case already consumes whatever the provider returns, and
 * [com.poyka.ripdpi.data.backup.BackupRestoreUseCase] already writes decoded
 * profiles through the sink. The data layer's restore path and its unit tests
 * exercise the real `ProxyProfile` round-trip via a fake sink, so only this DI seam
 * is pending.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun provideBackupProfileProvider(): BackupProfileProvider = BackupProfileProvider { emptyList<ProxyProfile>() }

    @Provides
    @Singleton
    fun provideBackupProfileRestoreSink(): BackupProfileRestoreSink =
        BackupProfileRestoreSink {
            // no-op until enumeration lands
        }
}
