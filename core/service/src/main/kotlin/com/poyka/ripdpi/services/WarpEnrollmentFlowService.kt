package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.WarpAccountKindConsumerFree
import com.poyka.ripdpi.data.WarpAccountKindConsumerPlus
import com.poyka.ripdpi.data.WarpAccountKindZeroTrust
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpSetupStateProvisioned
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface WarpEnrollmentFlowService {
    suspend fun registerConsumerFree(
        displayName: String,
        request: WarpRegisterDeviceRequest,
        profileId: String = DefaultWarpProfileId,
        networkScopeKey: String? = null,
    ): WarpEnrollmentSnapshot

    suspend fun refreshActiveProfile(networkScopeKey: String? = null): WarpEnrollmentSnapshot

    /** Frozen-profile runtime provisioning; concurrent user mutations must reject its commit. */
    suspend fun refreshProfileForRuntime(
        profileId: String,
        expectedCredentials: WarpCredentials,
        expectedRevision: Long,
        networkScopeKey: String?,
    ): WarpEnrollmentSnapshot
}

@Singleton
class DefaultWarpEnrollmentFlowService
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val profileStore: WarpProfileStore,
        private val credentialStore: WarpCredentialStore,
        private val endpointStore: WarpEndpointStore,
        private val provisioningClient: WarpProvisioningClient,
        private val bootstrapProxyRunner: WarpBootstrapProxyRunner,
        private val endpointScanner: WarpEndpointScanner,
        private val profileActivationService: WarpProfileActivationService,
        private val mutationLock: WarpStoreMutationLock,
        private val profileMutations: ProfileMutationCoordinator,
    ) : WarpEnrollmentFlowService {
        override suspend fun registerConsumerFree(
            displayName: String,
            request: WarpRegisterDeviceRequest,
            profileId: String,
            networkScopeKey: String?,
        ): WarpEnrollmentSnapshot {
            val preparation =
                profileMutations.captureMutation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation,
                )
            val normalizedProfileId = normalizeWarpProfileId(profileId, displayName)
            val provisioning =
                bootstrapProxyRunner.withBootstrapProxy {
                    provisioningClient.register(request, bootstrapProxy = it?.asOkHttpProxy())
                }
            val accountKind =
                if (provisioning.warpPlus || !provisioning.license.isNullOrBlank()) {
                    WarpAccountKindConsumerPlus
                } else {
                    WarpAccountKindConsumerFree
                }
            val profile =
                WarpProfile(
                    id = normalizedProfileId,
                    accountKind = accountKind,
                    displayName = displayName.ifBlank { normalizedProfileId },
                    setupState = WarpSetupStateProvisioned,
                    lastProvisionedAtEpochMillis = System.currentTimeMillis(),
                )
            val credentials =
                provisioning.credentials.toConsumerCredentials(
                    profileId = normalizedProfileId,
                    accountId = provisioning.accountId,
                    accountKind = accountKind,
                    displayName = profile.displayName,
                    license = provisioning.license,
                )
            return persistEnrollment {
                profileMutations.upsertWarp(
                    preparation,
                    profile = profile,
                    credentials = credentials,
                    endpoints = endpointStore.loadAll(profile.id),
                    activate = false,
                    scannerMode = WarpScannerModeAutomatic,
                )
                val endpoint =
                    endpointScanner.resolveEndpoint(
                        profileId = normalizedProfileId,
                        networkScopeKey = networkScopeKey.orEmpty(),
                        provisioned = provisioning.endpoint,
                        origin = WarpEndpointResolutionOrigin.UserMutation,
                    )
                profileMutations.upsertWarp(
                    com.poyka.ripdpi.data.ProfileMutationPreparation(
                        com.poyka.ripdpi.data.ProfileMutationOrigin.InternalReconcile,
                        preparation.expectedPauseAuthority,
                    ),
                    profile = profile,
                    credentials = credentials,
                    endpoints = endpointStore.loadAll(profile.id),
                    activate = true,
                    scannerMode = WarpScannerModeAutomatic,
                )
                WarpEnrollmentSnapshot(profile = profile, credentials = credentials, endpoint = endpoint)
            }
        }

        override suspend fun refreshActiveProfile(networkScopeKey: String?): WarpEnrollmentSnapshot {
            profileMutations.recover()
            val profile = requireActiveWarpProfile(appSettingsRepository, profileStore)
            val credentials = credentialStore.load(profile.id) ?: error("No WARP credentials saved")
            return refreshCapturedProfile(profile, credentials, networkScopeKey, runtimeRevision = null)
        }

        override suspend fun refreshProfileForRuntime(
            profileId: String,
            expectedCredentials: WarpCredentials,
            expectedRevision: Long,
            networkScopeKey: String?,
        ): WarpEnrollmentSnapshot {
            val captured =
                profileMutations.readRecovered {
                    val profile = profileStore.load(profileId) ?: error("Runtime WARP profile is unavailable")
                    check(
                        profileMutations.warpRuntimeRevision(profileId) == expectedRevision &&
                            credentialStore.load(profileId) == expectedCredentials,
                    ) { "Runtime WARP credentials changed before provisioning" }
                    Triple(profile, expectedCredentials, profileMutations.warpRuntimeRevision(profileId))
                }
            return refreshCapturedProfile(
                captured.first,
                captured.second,
                networkScopeKey,
                runtimeRevision = captured.third,
            )
        }

        private suspend fun refreshCapturedProfile(
            activeProfile: WarpProfile,
            credentials: WarpCredentials,
            networkScopeKey: String?,
            runtimeRevision: Long?,
        ): WarpEnrollmentSnapshot {
            check(activeProfile.accountKind != WarpAccountKindZeroTrust) {
                "Zero Trust profiles require reenrollment instead of consumer refresh"
            }
            val provisioning = refreshConsumerProvisioning(activeProfile, credentials, runtimeRevision)
            val refreshedCredentials =
                provisioning.credentials.copy(
                    profileId = activeProfile.id,
                    accountId = provisioning.accountId,
                    accountKind = activeProfile.accountKind,
                    displayName = activeProfile.displayName,
                    zeroTrustOrg = activeProfile.zeroTrustOrg,
                    license = credentials.license ?: provisioning.license,
                    peerPublicKey = provisioning.peerPublicKey,
                    interfaceAddressV4 = provisioning.interfaceAddressV4,
                    interfaceAddressV6 = provisioning.interfaceAddressV6,
                )
            val refreshedProfile =
                activeProfile.copy(
                    setupState = WarpSetupStateProvisioned,
                    lastProvisionedAtEpochMillis = System.currentTimeMillis(),
                )
            return persistEnrollment {
                if (runtimeRevision == null) {
                    profileMutations.upsertWarp(
                        profileMutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        refreshedProfile,
                        refreshedCredentials,
                        endpointStore.loadAll(activeProfile.id),
                        false,
                        WarpScannerModeAutomatic,
                    )
                }
                val proposedEndpoint =
                    endpointScanner.resolveEndpoint(
                        profileId = activeProfile.id,
                        networkScopeKey = networkScopeKey.orEmpty(),
                        provisioned = provisioning.endpoint,
                        origin =
                            if (runtimeRevision ==
                                null
                            ) {
                                WarpEndpointResolutionOrigin.UserMutation
                            } else {
                                WarpEndpointResolutionOrigin.RuntimeProvisioning
                            },
                    )
                val endpoint = normalizeWarpEndpoint(activeProfile.id, networkScopeKey, proposedEndpoint)
                if (runtimeRevision == null && endpoint != null) endpointStore.save(endpoint)
                if (runtimeRevision == null) {
                    profileMutations.upsertWarp(
                        profileMutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        refreshedProfile,
                        refreshedCredentials,
                        endpointStore.loadAll(activeProfile.id),
                        true,
                        WarpScannerModeAutomatic,
                    )
                } else {
                    commitRuntimeProvisioning(
                        refreshedProfile,
                        refreshedCredentials,
                        endpoint,
                        credentials,
                        runtimeRevision,
                    )
                }
                WarpEnrollmentSnapshot(
                    profile = refreshedProfile,
                    credentials = refreshedCredentials,
                    endpoint = endpoint,
                )
            }
        }

        private suspend fun commitRuntimeProvisioning(
            profile: WarpProfile,
            refreshed: WarpCredentials,
            endpoint: com.poyka.ripdpi.data.WarpEndpointCacheEntry?,
            before: WarpCredentials,
            revision: Long,
        ) {
            check(
                profileMutations.upsertWarpForRuntimeProvisioning(
                    profile,
                    refreshed,
                    endpointStore.loadAll(profile.id).filter {
                        it.networkScopeKey !=
                            endpoint?.networkScopeKey
                    } +
                        listOfNotNull(endpoint),
                    false,
                    WarpScannerModeAutomatic,
                    before,
                    revision,
                ),
            ) { "Runtime WARP provisioning was superseded by a user mutation" }
        }

        private suspend fun refreshConsumerProvisioning(
            profile: WarpProfile,
            credentials: WarpCredentials,
            runtimeRevision: Long?,
        ): WarpProvisioningResult =
            try {
                bootstrapProxyRunner.withBootstrapProxy {
                    provisioningClient.refresh(credentials, bootstrapProxy = it?.asOkHttpProxy())
                }
            } catch (error: WarpProvisioningException.AuthFailure) {
                reportRefreshFailure(profile, runtimeRevision, error)
            } catch (error: WarpProvisioningException.MalformedResponse) {
                reportRefreshFailure(profile, runtimeRevision, error)
            } catch (error: IOException) {
                reportRefreshFailure(profile, runtimeRevision, error)
            }

        private suspend fun reportRefreshFailure(
            profile: WarpProfile,
            runtimeRevision: Long?,
            error: Exception,
        ): Nothing {
            val authenticationFailure =
                error is WarpProvisioningException.AuthFailure ||
                    error is WarpProvisioningException.MalformedResponse ||
                    error.message.orEmpty().let { it.contains("HTTP 401") || it.contains("HTTP 403") }
            if (runtimeRevision == null &&
                authenticationFailure
            ) {
                profileActivationService.markProfileNeedsAttention(profile)
            }
            throw error
        }

        private suspend fun <T> persistEnrollment(operation: suspend () -> T): T =
            mutationLock.mutex.withLock {
                profileMutations.recover()
                operation()
            }

        private fun WarpCredentials.toConsumerCredentials(
            profileId: String,
            accountId: String?,
            accountKind: String,
            displayName: String,
            license: String?,
        ): WarpCredentials =
            copy(
                profileId = profileId,
                accountId = accountId,
                accountKind = accountKind,
                displayName = displayName,
                license = license,
            )
    }
