package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.backup.BackupProfileProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the backup-export feature.
 *
 * [BackupProfileProvider] currently resolves to an empty list: proxy profiles are
 * persisted as per-id encrypted blobs in `:core:service`
 * ([com.poyka.ripdpi.keystore.EncryptedProfileStore]) with no enumeration API, so
 * there is no single live `List<ProxyProfile>` to gather yet. Groups, routing
 * rules, and settings ARE exported. The follow-up restore task should replace this
 * binding with a provider backed by a real profile-enumeration source once one
 * exists; the gather use case already consumes whatever the provider returns.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun provideBackupProfileProvider(): BackupProfileProvider = BackupProfileProvider { emptyList<ProxyProfile>() }
}
