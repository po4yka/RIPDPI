package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Clears session state outside the profile database after the canonical reset commits. */
fun interface UserSessionResetStore {
    suspend fun clearAll()
}

@Singleton
internal class DefaultUserSessionResetStore
    @Inject
    constructor(
        private val bootSession: BootSessionStateStore,
        private val workerTransport: WsTunnelWorkerCredentialStore,
    ) : UserSessionResetStore {
        override suspend fun clearAll() {
            bootSession.clearAll()
            workerTransport.clearAll()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class UserSessionResetStoreModule {
    @Binds abstract fun bind(store: DefaultUserSessionResetStore): UserSessionResetStore
}
