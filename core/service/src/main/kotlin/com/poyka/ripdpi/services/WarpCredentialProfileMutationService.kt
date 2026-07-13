package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.WarpAccountKindConsumerFree
import com.poyka.ripdpi.data.WarpAccountKindConsumerPlus
import com.poyka.ripdpi.data.WarpAccountKindZeroTrust
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpScannerModeManual
import com.poyka.ripdpi.data.WarpSetupStateProvisioned
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

interface WarpCredentialProfileMutationService {
    suspend fun attachWarpPlusLicense(
        profileId: String,
        license: String,
    ): WarpEnrollmentSnapshot

    suspend fun removeWarpPlusLicense(profileId: String): WarpEnrollmentSnapshot

    suspend fun importZeroTrustProfile(request: WarpZeroTrustImportRequest): WarpEnrollmentSnapshot

    suspend fun resetProfile(profileId: String)
}

@Singleton
class DefaultWarpCredentialProfileMutationService
    @Inject
    constructor(
        private val profileStore: WarpProfileStore,
        private val credentialStore: WarpCredentialStore,
        private val endpointStore: WarpEndpointStore,
        private val mutationLock: WarpStoreMutationLock,
        private val profileMutations: ProfileMutationCoordinator,
    ) : WarpCredentialProfileMutationService {
        override suspend fun attachWarpPlusLicense(
            profileId: String,
            license: String,
        ): WarpEnrollmentSnapshot =
            mutateProfile(profileId) { profile, credentials ->
                require(license.isNotBlank()) { "WARP+ license must not be blank" }
                val updatedProfile =
                    profile.copy(
                        accountKind = WarpAccountKindConsumerPlus,
                        setupState = WarpSetupStateProvisioned,
                    )
                val updatedCredentials =
                    credentials.copy(
                        accountKind = WarpAccountKindConsumerPlus,
                        license = license,
                    )
                updatedProfile to updatedCredentials
            }

        override suspend fun removeWarpPlusLicense(profileId: String): WarpEnrollmentSnapshot =
            mutateProfile(profileId) { profile, credentials ->
                val updatedProfile =
                    profile.copy(
                        accountKind = WarpAccountKindConsumerFree,
                        setupState = WarpSetupStateProvisioned,
                    )
                val updatedCredentials =
                    credentials.copy(
                        accountKind = WarpAccountKindConsumerFree,
                        license = null,
                    )
                updatedProfile to updatedCredentials
            }

        override suspend fun importZeroTrustProfile(request: WarpZeroTrustImportRequest): WarpEnrollmentSnapshot {
            require(request.displayName.isNotBlank()) { "Zero Trust display name must not be blank" }
            require(request.organization.isNotBlank()) { "Zero Trust organization must not be blank" }
            require(request.deviceId.isNotBlank()) { "Zero Trust device id must not be blank" }
            require(request.accessToken.isNotBlank()) { "Zero Trust access token must not be blank" }
            val profileId = normalizeWarpProfileId(request.profileId, request.displayName)
            val profile =
                WarpProfile(
                    id = profileId,
                    accountKind = WarpAccountKindZeroTrust,
                    displayName = request.displayName,
                    zeroTrustOrg = request.organization,
                    setupState = WarpSetupStateProvisioned,
                    lastProvisionedAtEpochMillis = System.currentTimeMillis(),
                )
            val credentials =
                WarpCredentials(
                    profileId = profileId,
                    deviceId = request.deviceId,
                    accessToken = request.accessToken,
                    accountKind = WarpAccountKindZeroTrust,
                    displayName = request.displayName,
                    zeroTrustOrg = request.organization,
                    refreshToken = request.refreshToken,
                    clientId = request.clientId,
                    privateKey = request.privateKey,
                    publicKey = request.publicKey,
                    peerPublicKey = request.peerPublicKey,
                    interfaceAddressV4 = request.interfaceAddressV4,
                    interfaceAddressV6 = request.interfaceAddressV6,
                )
            return mutationLock.mutex.withLock {
                profileMutations.upsertWarp(
                    profile = profile,
                    credentials = credentials,
                    endpoints = endpointStore.loadAll(profileId),
                    activate = true,
                    scannerMode = WarpScannerModeManual,
                )
                WarpEnrollmentSnapshot(profile = profile, credentials = credentials, endpoint = null)
            }
        }

        override suspend fun resetProfile(profileId: String) =
            mutationLock.mutex.withLock {
                profileMutations.recover()
                profileMutations.deleteWarp(
                    profileId = profileId,
                    clearActive = profileStore.activeProfileId() == profileId,
                )
                Unit
            }

        private suspend fun mutateProfile(
            profileId: String,
            transform: suspend (WarpProfile, WarpCredentials) -> Pair<WarpProfile, WarpCredentials>,
        ): WarpEnrollmentSnapshot =
            mutationLock.mutex.withLock {
                profileMutations.recover()
                val profile = profileStore.load(profileId) ?: error("No WARP profile found for $profileId")
                val credentials = credentialStore.load(profileId) ?: error("No WARP credentials found for $profileId")
                val (updatedProfile, updatedCredentials) = transform(profile, credentials)
                val activate = profileStore.activeProfileId() == profileId
                profileMutations.upsertWarp(
                    profile = updatedProfile,
                    credentials = updatedCredentials,
                    endpoints = endpointStore.loadAll(profileId),
                    activate = activate,
                    scannerMode = WarpScannerModeManual,
                )
                WarpEnrollmentSnapshot(
                    profile = updatedProfile,
                    credentials = updatedCredentials,
                    endpoint = endpointStore.load(profileId, GlobalWarpEndpointScopeKey),
                )
            }
    }
