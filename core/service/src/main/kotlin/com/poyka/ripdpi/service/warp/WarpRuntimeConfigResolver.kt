@file:Suppress("TooGenericExceptionCaught")

package com.poyka.ripdpi.service.warp

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig
import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpManualEndpointConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.WarpAccountKindZeroTrust
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpScannerModeManual
import com.poyka.ripdpi.services.RelayRuntimeNetworkMode
import com.poyka.ripdpi.services.WarpEndpointResolutionOrigin
import com.poyka.ripdpi.services.WarpEndpointScanContext
import com.poyka.ripdpi.services.WarpEndpointScanner
import com.poyka.ripdpi.services.WarpEnrollmentOrchestrator
import com.poyka.ripdpi.services.WarpProvisioningException
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

internal interface WarpRuntimeConfigResolver {
    suspend fun resolve(
        config: RipDpiWarpConfig,
        requestedReference: com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference?,
        networkScopeKey: String? = null,
        networkMode: RelayRuntimeNetworkMode = RelayRuntimeNetworkMode.Vpn,
    ): ResolvedWarpRuntimeStart
}

@Singleton
internal class DefaultWarpRuntimeConfigResolver
    @Inject
    constructor(
        private val credentialStore: WarpCredentialStore,
        private val endpointStore: WarpEndpointStore,
        private val enrollmentOrchestrator: WarpEnrollmentOrchestrator,
        private val profileMutations: ProfileMutationCoordinator,
        private val profileStore: WarpProfileStore,
        private val endpointScanner: WarpEndpointScanner,
    ) : WarpRuntimeConfigResolver {
        override suspend fun resolve(
            config: RipDpiWarpConfig,
            requestedReference: RequestedWarpRuntimeReference?,
            networkScopeKey: String?,
            networkMode: RelayRuntimeNetworkMode,
        ): ResolvedWarpRuntimeStart {
            require(config.enabled) { "WARP runtime requested while disabled" }
            val reference = checkNotNull(requestedReference) { "WARP requires a frozen profile reference" }
            val profileId = reference.profileId.ifBlank { error("No WARP profile configured for this start") }
            val scopeKey = networkScopeKey?.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey
            val captured = captureProfileSnapshot(profileId, reference.revision, scopeKey)
            val initialCredentials = captured.second
            // The provider cannot restore a lost local key pair through GET /reg/{id}.
            requiredWarpKey(initialCredentials.privateKey, "WARP private key missing")
            requiredWarpKey(initialCredentials.publicKey, "WARP public key missing")
            val context =
                WarpEndpointScanContext(
                    initialCredentials,
                    config,
                    if (initialCredentials.accountKind ==
                        WarpAccountKindZeroTrust
                    ) {
                        WarpScannerModeManual
                    } else {
                        WarpScannerModeAutomatic
                    },
                    if (networkMode ==
                        RelayRuntimeNetworkMode.Vpn
                    ) {
                        RelaySocketProtection.VpnRequired
                    } else {
                        RelaySocketProtection.Inactive
                    },
                )
            val needsRefresh =
                initialCredentials.peerPublicKey.isNullOrBlank() ||
                    initialCredentials.interfaceAddressV4.isNullOrBlank()
            val provisioning =
                if (needsRefresh) {
                    check(initialCredentials.accountKind != WarpAccountKindZeroTrust) {
                        "Zero Trust profiles require a complete local import"
                    }
                    refreshProvisioning(profileId, initialCredentials, reference.revision, scopeKey, context)
                } else {
                    null
                }
            val credentials = provisioning?.credentials ?: initialCredentials
            val endpoint =
                if (config.endpointSelectionMode == "manual") {
                    config.manualEndpoint.toResolvedEndpoint()
                } else {
                    val selected =
                        provisioning?.endpoint ?: if (provisioning == null) {
                            selectRuntimeEndpoint(captured.first, reference, context, scopeKey, captured.third)
                        } else {
                            null
                        }
                    selected?.toResolvedEndpoint() ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpEndpointUnavailable("No authenticated WARP endpoint is available"),
                    )
                }
            if (provisioning == null) {
                profileMutations.readRecovered {
                    check(
                        profileMutations.warpRuntimeRevision(profileId) == reference.revision &&
                            credentialStore.load(profileId) == credentials,
                    ) { "Runtime WARP credentials changed during resolution" }
                }
            }
            val resolved = resolveConsumedWarp(config, profileId, credentials, endpoint)
            val patch =
                provisioning?.let {
                    RuntimeWarpProvisioningPatch(profileId, initialCredentials, checkNotNull(it.credentials))
                }
            return ResolvedWarpRuntimeStart(resolved, patch)
        }

        private suspend fun captureProfileSnapshot(
            profileId: String,
            expectedRevision: Long,
            scopeKey: String,
        ): Triple<
            com.poyka.ripdpi.data.WarpProfile,
            com.poyka.ripdpi.data.WarpCredentials,
            com.poyka.ripdpi.data.WarpEndpointCacheEntry?,
        > =
            profileMutations.readRecovered {
                check(profileMutations.warpRuntimeRevision(profileId) == expectedRevision) {
                    "Runtime WARP profile changed before resolution"
                }
                val credentials =
                    checkNotNull(credentialStore.load(profileId)) { "WARP credentials are unavailable" }
                val profile = checkNotNull(profileStore.load(profileId)) { "WARP profile is unavailable" }
                Triple(
                    profile,
                    credentials,
                    endpointStore.load(profileId, scopeKey)
                        ?: endpointStore.load(profileId, GlobalWarpEndpointScopeKey),
                )
            }

        private suspend fun selectRuntimeEndpoint(
            profile: com.poyka.ripdpi.data.WarpProfile,
            reference: RequestedWarpRuntimeReference,
            context: WarpEndpointScanContext,
            scopeKey: String,
            provisioned: com.poyka.ripdpi.data.WarpEndpointCacheEntry?,
        ): com.poyka.ripdpi.data.WarpEndpointCacheEntry? {
            val selected =
                endpointScanner.resolveEndpoint(
                    profile.id,
                    scopeKey,
                    provisioned,
                    WarpEndpointResolutionOrigin.RuntimeProvisioning,
                    context,
                ) ?: return null
            check(
                profileMutations.upsertWarpForRuntimeProvisioning(
                    profile,
                    context.credentials,
                    endpointStore.loadAll(profile.id).filter { it.networkScopeKey != scopeKey } + selected,
                    false,
                    context.scannerMode,
                    context.credentials,
                    reference.revision,
                ),
            ) { "Runtime WARP endpoint selection was superseded by a user mutation" }
            return selected
        }

        private fun resolveConsumedWarp(
            config: RipDpiWarpConfig,
            profileId: String,
            credentials: com.poyka.ripdpi.data.WarpCredentials,
            endpoint: ResolvedRipDpiWarpEndpoint,
        ): ResolvedRipDpiWarpConfig {
            val privateKey = requiredWarpKey(credentials.privateKey, "WARP private key missing")
            val publicKey = requiredWarpKey(credentials.publicKey, "WARP public key missing")
            val peerPublicKey = requiredWarpKey(credentials.peerPublicKey, "WARP peer public key missing")
            return ResolvedRipDpiWarpConfig(
                enabled = config.enabled,
                profileId = profileId,
                accountKind = credentials.accountKind,
                deviceId = credentials.deviceId,
                accessToken = credentials.accessToken,
                clientId = credentials.clientId,
                privateKey = privateKey,
                publicKey = publicKey,
                peerPublicKey = peerPublicKey,
                interfaceAddressV4 = credentials.interfaceAddressV4,
                interfaceAddressV6 = credentials.interfaceAddressV6,
                endpoint = endpoint,
                routeMode = config.routeMode,
                routeHosts = config.routeHosts,
                builtInRulesEnabled = config.builtInRulesEnabled,
                endpointSelectionMode = config.endpointSelectionMode,
                manualEndpoint = config.manualEndpoint,
                scannerEnabled = config.scannerEnabled,
                scannerParallelism = config.scannerParallelism,
                scannerMaxRttMs = config.scannerMaxRttMs,
                amnezia = config.amnezia,
                localSocksHost = config.localSocksHost,
                localSocksPort = config.localSocksPort,
            )
        }

        private fun requiredWarpKey(
            value: String?,
            message: String,
        ): String =
            value?.takeIf(String::isNotBlank)
                ?: throw ServiceStartupRejectedException(FailureReason.WarpProvisioningFailed(message))

        private suspend fun refreshProvisioning(
            profileId: String,
            before: com.poyka.ripdpi.data.WarpCredentials,
            expectedRevision: Long,
            networkScopeKey: String,
            context: WarpEndpointScanContext,
        ): com.poyka.ripdpi.services.WarpEnrollmentSnapshot =
            try {
                enrollmentOrchestrator.refreshProfileForRuntime(
                    profileId,
                    before,
                    expectedRevision,
                    networkScopeKey,
                    context,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw error.toStartupRejectedException()
            }

        private fun Exception.toStartupRejectedException(): ServiceStartupRejectedException {
            val message =
                when (this) {
                    is WarpProvisioningException.AuthFailure -> {
                        message ?: "WARP provisioning authentication failed"
                    }

                    is WarpProvisioningException.MalformedResponse -> {
                        message ?: "WARP provisioning returned malformed data"
                    }

                    else -> {
                        message ?: "WARP provisioning refresh failed"
                    }
                }
            return ServiceStartupRejectedException(
                FailureReason.WarpProvisioningFailed(message),
            ).also { rejected ->
                rejected.initCause(this)
            }
        }

        private fun com.poyka.ripdpi.data.WarpEndpointCacheEntry.toResolvedEndpoint(): ResolvedRipDpiWarpEndpoint =
            ResolvedRipDpiWarpEndpoint(
                host = host.orEmpty(),
                ipv4 = ipv4,
                ipv6 = ipv6,
                port = port,
                source = source,
            )

        private fun RipDpiWarpManualEndpointConfig.toResolvedEndpoint(): ResolvedRipDpiWarpEndpoint {
            val normalizedHost = host.ifBlank { ipv4.ifBlank { ipv6 } }
            require(normalizedHost.isNotBlank()) { "Manual WARP endpoint host is blank" }
            return ResolvedRipDpiWarpEndpoint(
                host = normalizedHost,
                ipv4 = ipv4.takeIf(String::isNotBlank),
                ipv6 = ipv6.takeIf(String::isNotBlank),
                port = port,
                source = "manual",
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WarpRuntimeConfigResolverModule {
    @Binds
    @Singleton
    abstract fun bindWarpRuntimeConfigResolver(resolver: DefaultWarpRuntimeConfigResolver): WarpRuntimeConfigResolver
}
