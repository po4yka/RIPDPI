@file:Suppress("TooGenericExceptionCaught")

package com.poyka.ripdpi.service.warp

import com.poyka.ripdpi.core.ResolvedRipDpiWarpConfig
import com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpManualEndpointConfig
import com.poyka.ripdpi.data.AppSettingsRepository
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
import javax.inject.Inject
import javax.inject.Singleton

internal interface WarpRuntimeConfigResolver {
    suspend fun resolve(config: RipDpiWarpConfig): ResolvedRipDpiWarpConfig
}

@Singleton
internal class DefaultWarpRuntimeConfigResolver
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val credentialStore: WarpCredentialStore,
        private val endpointStore: WarpEndpointStore,
        private val enrollmentOrchestrator: WarpEnrollmentOrchestrator,
        private val profileMutations: ProfileMutationCoordinator,
    ) : WarpRuntimeConfigResolver {
        override suspend fun resolve(config: RipDpiWarpConfig): ResolvedRipDpiWarpConfig {
            require(config.enabled) { "WARP runtime requested while disabled" }
            profileMutations.recover()
            val profileId =
                appSettingsRepository
                    .snapshot()
                    .warpProfileId
                    .ifBlank { error("No active WARP profile configured") }
            val initialCredentials = credentialStore.load(profileId)
            val initialEndpoint =
                if (config.endpointSelectionMode == "manual") {
                    null
                } else {
                    endpointStore.load(profileId, GlobalWarpEndpointScopeKey)
                }
            if (needsRefresh(initialCredentials, initialEndpoint)) {
                refreshProvisioning()
            }
            val credentials =
                credentialStore.load(profileId)
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("Missing WARP credentials for profile $profileId"),
                    )
            val endpoint =
                when (config.endpointSelectionMode) {
                    "manual" -> {
                        config.manualEndpoint.toResolvedEndpoint()
                    }

                    else -> {
                        endpointStore
                            .load(profileId, GlobalWarpEndpointScopeKey)
                            ?.toResolvedEndpoint()
                    }
                } ?: throw ServiceStartupRejectedException(
                    FailureReason.WarpEndpointUnavailable("Missing WARP endpoint for profile $profileId"),
                )
            val privateKey =
                credentials.privateKey?.takeIf(String::isNotBlank)
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("WARP private key missing"),
                    )
            val publicKey =
                credentials.publicKey?.takeIf(String::isNotBlank)
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("WARP public key missing"),
                    )
            val peerPublicKey =
                credentials.peerPublicKey?.takeIf(String::isNotBlank)
                    ?: throw ServiceStartupRejectedException(
                        FailureReason.WarpProvisioningFailed("WARP peer public key missing"),
                    )
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

        private suspend fun refreshProvisioning() {
            try {
                enrollmentOrchestrator.refreshActiveProfile(GlobalWarpEndpointScopeKey)
            } catch (error: Exception) {
                throw error.toStartupRejectedException()
            }
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
        ): Boolean =
            credentials == null ||
                credentials.privateKey.isNullOrBlank() ||
                credentials.publicKey.isNullOrBlank() ||
                credentials.peerPublicKey.isNullOrBlank() ||
                endpoint == null

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
