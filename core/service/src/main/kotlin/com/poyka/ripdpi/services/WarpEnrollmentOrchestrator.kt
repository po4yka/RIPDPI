package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DefaultWarpProfileId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

interface WarpEnrollmentOrchestrator {
    suspend fun registerConsumerFree(
        displayName: String,
        request: WarpRegisterDeviceRequest,
        profileId: String = DefaultWarpProfileId,
        networkScopeKey: String? = null,
    ): WarpEnrollmentSnapshot

    suspend fun attachWarpPlusLicense(
        profileId: String,
        license: String,
    ): WarpEnrollmentSnapshot

    suspend fun removeWarpPlusLicense(profileId: String): WarpEnrollmentSnapshot

    suspend fun importZeroTrustProfile(request: WarpZeroTrustImportRequest): WarpEnrollmentSnapshot

    suspend fun completeZeroTrustBrowserEnrollment(request: WarpZeroTrustImportRequest): WarpEnrollmentSnapshot

    suspend fun refreshActiveProfile(networkScopeKey: String? = null): WarpEnrollmentSnapshot

    suspend fun resetProfile(profileId: String)
}

@Singleton
class DefaultWarpEnrollmentOrchestrator
    @Inject
    constructor(
        private val enrollmentFlowService: WarpEnrollmentFlowService,
        private val credentialProfileMutationService: WarpCredentialProfileMutationService,
    ) : WarpEnrollmentOrchestrator {
        private val mutex = Mutex()

        override suspend fun registerConsumerFree(
            displayName: String,
            request: WarpRegisterDeviceRequest,
            profileId: String,
            networkScopeKey: String?,
        ): WarpEnrollmentSnapshot =
            mutex.withLock {
                enrollmentFlowService.registerConsumerFree(
                    displayName = displayName,
                    request = request,
                    profileId = profileId,
                    networkScopeKey = networkScopeKey,
                )
            }

        override suspend fun attachWarpPlusLicense(
            profileId: String,
            license: String,
        ): WarpEnrollmentSnapshot =
            mutex.withLock {
                credentialProfileMutationService.attachWarpPlusLicense(
                    profileId = profileId,
                    license = license,
                )
            }

        override suspend fun removeWarpPlusLicense(profileId: String): WarpEnrollmentSnapshot =
            mutex.withLock {
                credentialProfileMutationService.removeWarpPlusLicense(profileId)
            }

        override suspend fun importZeroTrustProfile(request: WarpZeroTrustImportRequest): WarpEnrollmentSnapshot =
            mutex.withLock {
                credentialProfileMutationService.importZeroTrustProfile(request)
            }

        override suspend fun completeZeroTrustBrowserEnrollment(
            request: WarpZeroTrustImportRequest,
        ): WarpEnrollmentSnapshot =
            mutex.withLock {
                credentialProfileMutationService.importZeroTrustProfile(request)
            }

        override suspend fun refreshActiveProfile(networkScopeKey: String?): WarpEnrollmentSnapshot =
            mutex.withLock {
                enrollmentFlowService.refreshActiveProfile(networkScopeKey)
            }

        override suspend fun resetProfile(profileId: String) {
            mutex.withLock {
                credentialProfileMutationService.resetProfile(profileId)
            }
        }
    }
