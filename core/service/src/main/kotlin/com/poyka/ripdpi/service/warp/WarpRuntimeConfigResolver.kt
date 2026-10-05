@file:Suppress("TooGenericExceptionCaught")

package com.poyka.ripdpi.service.warp

import com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig
import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpManualEndpointConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointStore
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
    ) : WarpRuntimeConfigResolver {
        override suspend fun resolve(
            config: RipDpiWarpConfig,
            requestedReference: com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference?,
        ): ResolvedWarpRuntimeStart {
            require(config.enabled) { "WARP runtime requested while disabled" }
            profileMutations.recover()
            val profileId =
                checkNotNull(requestedReference) {
                    "WARP requires a frozen profile reference"
                }.profileId.ifBlank { error("No WARP profile configured for this start") }
            val initialCredentials = credentialStore.load(profileId)
            val initialEndpoint =
                if (config.endpointSelectionMode == "manual") {
                    null
                } else {
                    endpointStore.load(profileId, GlobalWarpEndpointScopeKey)
                }
            val provisioning =
                if (needsRefresh(
                        initialCredentials,
                        initialEndpoint,
                        config.endpointSelectionMode != "manual",
                    )
                ) {
                    refreshProvisioning(
                        profileId,
                        checkNotNull(initialCredentials) { "WARP provisioning credentials are unavailable" },
                        checkNotNull(requestedReference).revision,
                    )
                } else {
                    null
                }
            val credentials =
                provisioning?.credentials ?: initialCredentials
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("Missing WARP credentials for profile $profileId"),
                    )
            val endpoint =
                when (config.endpointSelectionMode) {
                    "manual" -> {
                        config.manualEndpoint.toResolvedEndpoint()
                    }

                    else -> {
                        provisioning?.endpoint?.toResolvedEndpoint() ?: initialEndpoint?.toResolvedEndpoint()
                    }
                } ?: throw ServiceStartupRejectedException(
                    FailureReason.WarpEndpointUnavailable("Missing WARP endpoint for profile $profileId"),
                )
            val resolved = resolveConsumedWarp(config, profileId, credentials, endpoint)
            val patch =
                provisioning?.let {
                    RuntimeWarpProvisioningPatch(
                        profileId,
                        checkNotNull(initialCredentials),
                        checkNotNull(it.credentials),
                    )
                }
            return ResolvedWarpRuntimeStart(resolved, patch)
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
        ): com.poyka.ripdpi.services.WarpEnrollmentSnapshot =
            try {
                enrollmentOrchestrator.refreshProfileForRuntime(
                    profileId,
                    before,
                    expectedRevision,
                    GlobalWarpEndpointScopeKey,
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

        private fun needsRefresh(
            credentials: com.poyka.ripdpi.data.WarpCredentials?,
            endpoint: com.poyka.ripdpi.data.WarpEndpointCacheEntry?,
            requiresEndpoint: Boolean,
        ): Boolean =
            credentials == null ||
                credentials.privateKey.isNullOrBlank() ||
                credentials.publicKey.isNullOrBlank() ||
                credentials.peerPublicKey.isNullOrBlank() ||
                (requiresEndpoint && endpoint == null)

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
