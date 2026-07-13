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
                    )
                profileMutations.upsertWarp(
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
            val activeProfile = requireActiveWarpProfile(appSettingsRepository, profileStore)
            check(activeProfile.accountKind != WarpAccountKindZeroTrust) {
                "Zero Trust profiles require reenrollment instead of consumer refresh"
            }
            val credentials =
                credentialStore.load(activeProfile.id)
                    ?: error("No WARP credentials saved for profile ${activeProfile.id}")
            val provisioning =
                try {
                    bootstrapProxyRunner.withBootstrapProxy {
                        provisioningClient.refresh(credentials, bootstrapProxy = it?.asOkHttpProxy())
                    }
                } catch (error: WarpProvisioningException.AuthFailure) {
                    profileActivationService.markProfileNeedsAttention(activeProfile)
                    throw error
                } catch (error: WarpProvisioningException.MalformedResponse) {
                    profileActivationService.markProfileNeedsAttention(activeProfile)
                    throw error
                } catch (error: IOException) {
                    if (error.message.orEmpty().contains("HTTP 401") ||
                        error.message.orEmpty().contains("HTTP 403")
                    ) {
                        profileActivationService.markProfileNeedsAttention(activeProfile)
                    }
                    throw error
                }
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
                profileMutations.upsertWarp(
                    profile = refreshedProfile,
                    credentials = refreshedCredentials,
                    endpoints = endpointStore.loadAll(activeProfile.id),
                    activate = false,
                    scannerMode = WarpScannerModeAutomatic,
                )
                val endpoint =
                    saveWarpEndpoint(
                        endpointStore = endpointStore,
                        profileId = activeProfile.id,
                        networkScopeKey = networkScopeKey,
                        entry =
                            endpointScanner.resolveEndpoint(
                                profileId = activeProfile.id,
                                networkScopeKey = networkScopeKey.orEmpty(),
                                provisioned = provisioning.endpoint,
                            ),
                    )
                profileMutations.upsertWarp(
                    profile = refreshedProfile,
                    credentials = refreshedCredentials,
                    endpoints = endpointStore.loadAll(activeProfile.id),
                    activate = true,
                    scannerMode = WarpScannerModeAutomatic,
                )
                WarpEnrollmentSnapshot(
                    profile = refreshedProfile,
                    credentials = refreshedCredentials,
                    endpoint = endpoint,
                )
            }
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
