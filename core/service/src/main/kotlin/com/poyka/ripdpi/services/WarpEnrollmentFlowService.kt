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
        scanContext: WarpEndpointScanContext? = null,
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
            val scanSettings = appSettingsRepository.snapshot()
            val normalizedProfileId = normalizeWarpProfileId(profileId, displayName)
            val provisioning =
                bootstrapProxyRunner.withBootstrapProxy {
                    provisioningClient.register(request, bootstrapProxy = it?.asOkHttpProxy())
                }
            val accountKind = provisioning.consumerAccountKind()
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
            val completeCredentials =
                credentials.copy(
                    peerPublicKey = provisioning.peerPublicKey,
                    interfaceAddressV4 = provisioning.interfaceAddressV4,
                    interfaceAddressV6 = provisioning.interfaceAddressV6,
                )
            val endpoint =
                endpointScanner.resolveEndpoint(
                    profileId = normalizedProfileId,
                    networkScopeKey = networkScopeKey.orEmpty(),
                    provisioned = provisioning.endpoint,
                    origin = WarpEndpointResolutionOrigin.UserMutation,
                    context = WarpEndpointScanContext.capture(completeCredentials, scanSettings),
                )
            return persistEnrollment {
                profileMutations
                    .upsertWarp(
                        preparation,
                        profile = profile,
                        credentials = completeCredentials,
                        endpoints = enrollmentEndpoints(profile.id, networkScopeKey, endpoint),
                        activate = true,
                        scannerMode = WarpScannerModeAutomatic,
                    ).requireAppliedWarpMutation()
                WarpEnrollmentSnapshot(profile = profile, credentials = completeCredentials, endpoint = endpoint)
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
            scanContext: WarpEndpointScanContext?,
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
                scanContext = scanContext,
            )
        }

        private suspend fun refreshCapturedProfile(
            activeProfile: WarpProfile,
            credentials: WarpCredentials,
            networkScopeKey: String?,
            runtimeRevision: Long?,
            scanContext: WarpEndpointScanContext? = null,
        ): WarpEnrollmentSnapshot {
            check(activeProfile.accountKind != WarpAccountKindZeroTrust) {
                "Zero Trust profiles require reenrollment instead of consumer refresh"
            }
            val preparation = profileMutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit)
            val scanSettings = appSettingsRepository.snapshot()
            val provisioning = refreshConsumerProvisioning(activeProfile, credentials, runtimeRevision)
            val refreshedCredentials = mergeRefreshedCredentials(activeProfile, credentials, provisioning)
            val refreshedProfile =
                activeProfile.copy(
                    accountKind = refreshedCredentials.accountKind,
                    setupState = WarpSetupStateProvisioned,
                    lastProvisionedAtEpochMillis = System.currentTimeMillis(),
                )
            val endpoint =
                normalizeWarpEndpoint(
                    activeProfile.id,
                    networkScopeKey,
                    if (scanContext?.config?.endpointSelectionMode ==
                        "manual"
                    ) {
                        provisioning.endpoint
                    } else {
                        endpointScanner.resolveEndpoint(
                            profileId = activeProfile.id,
                            networkScopeKey = networkScopeKey.orEmpty(),
                            provisioned = provisioning.endpoint,
                            origin =
                                if (runtimeRevision == null) {
                                    WarpEndpointResolutionOrigin.UserMutation
                                } else {
                                    WarpEndpointResolutionOrigin.RuntimeProvisioning
                                },
                            context =
                                scanContext?.withCredentials(refreshedCredentials)
                                    ?: WarpEndpointScanContext.capture(refreshedCredentials, scanSettings),
                        )
                    },
                )
            return persistEnrollment {
                if (runtimeRevision == null) {
                    profileMutations
                        .upsertWarp(
                            preparation,
                            refreshedProfile,
                            refreshedCredentials,
                            endpointStore
                                .loadAll(
                                    activeProfile.id,
                                ).filter { it.networkScopeKey != endpoint?.networkScopeKey } +
                                listOfNotNull(endpoint),
                            false,
                            WarpScannerModeAutomatic,
                        ).requireAppliedWarpMutation()
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

        private fun mergeRefreshedCredentials(
            profile: WarpProfile,
            before: WarpCredentials,
            provisioning: WarpProvisioningResult,
        ): WarpCredentials =
            provisioning.credentials.copy(
                profileId = profile.id,
                accountId = provisioning.accountId,
                accountKind = provisioning.consumerAccountKind(),
                displayName = profile.displayName,
                zeroTrustOrg = profile.zeroTrustOrg,
                license = before.license ?: provisioning.license,
                peerPublicKey = provisioning.peerPublicKey,
                interfaceAddressV4 = provisioning.interfaceAddressV4,
                interfaceAddressV6 = provisioning.interfaceAddressV6,
            )

        private fun enrollmentEndpoints(
            profileId: String,
            networkScopeKey: String?,
            endpoint: com.poyka.ripdpi.data.WarpEndpointCacheEntry?,
        ): List<com.poyka.ripdpi.data.WarpEndpointCacheEntry> =
            listOfNotNull(normalizeWarpEndpoint(profileId, networkScopeKey, endpoint))
                .flatMap {
                    listOf(it, it.copy(networkScopeKey = com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey))
                }.distinctBy { it.networkScopeKey }

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

private fun WarpProvisioningResult.consumerAccountKind(): String =
    if (warpPlus || accountType.trim().lowercase() in setOf("limited", "unlimited")) {
        WarpAccountKindConsumerPlus
    } else {
        WarpAccountKindConsumerFree
    }
