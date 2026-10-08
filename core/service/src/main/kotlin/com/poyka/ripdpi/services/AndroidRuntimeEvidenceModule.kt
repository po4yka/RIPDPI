package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DiagnosticsNetworkEpochProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AndroidRuntimeEvidenceModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsNetworkEpochProvider(epoch: CandidateRelayNetworkEpoch): DiagnosticsNetworkEpochProvider

    @Binds
    @Singleton
    abstract fun bindAndroidRuntimeEvidenceClock(clock: SystemAndroidRuntimeEvidenceClock): AndroidRuntimeEvidenceClock
}
