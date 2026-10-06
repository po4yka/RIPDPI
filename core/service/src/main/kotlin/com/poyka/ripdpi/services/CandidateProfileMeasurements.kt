package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.xray.XrayProfile
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Singleton

/** Owns candidate cleanup until release is proven, including cancellation and timeout. */
interface CandidateMeasurementCleanup {
    val cleanupPending: StateFlow<Boolean>

    suspend fun retryCleanup(): Boolean
}

interface CandidateRelayMeasurements : CandidateMeasurementCleanup {
    suspend fun captureEnvironment(): CandidateRelayProbeEnvironment

    suspend fun measure(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        probeUrl: String,
    ): CandidateRelayMeasurement
}

interface CandidateXrayMeasurements : CandidateMeasurementCleanup {
    suspend fun measure(
        profile: XrayProfile,
        url: String,
    ): CandidateRelayMeasurement
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CandidateProfileMeasurementModule {
    @Binds
    @Singleton
    abstract fun relay(probe: CandidateRelayPayloadProbe): CandidateRelayMeasurements

    @Binds
    @Singleton
    abstract fun xray(probe: CandidateXrayPayloadProbe): CandidateXrayMeasurements
}
