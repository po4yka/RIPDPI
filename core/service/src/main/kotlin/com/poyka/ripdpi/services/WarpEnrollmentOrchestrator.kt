package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.WarpAccountKindConsumerFree
import com.poyka.ripdpi.data.WarpAccountKindConsumerPlus
import com.poyka.ripdpi.data.WarpAccountKindZeroTrust
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpScannerModeManual
import com.poyka.ripdpi.data.WarpSetupStateNeedsAttention
import com.poyka.ripdpi.data.WarpSetupStateNotConfigured
import com.poyka.ripdpi.data.WarpSetupStateProvisioned
import com.poyka.ripdpi.data.normalizeWarpAccountKind
import com.poyka.ripdpi.data.normalizeWarpScannerMode
import com.poyka.ripdpi.data.normalizeWarpSetupState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class WarpEnrollmentSnapshot(
    val profile: WarpProfile,
    val credentials: WarpCredentials? = null,
    val endpoint: WarpEndpointCacheEntry? = null,
)

data class WarpZeroTrustImportRequest(
    val profileId: String = DefaultWarpProfileId,
    val displayName: String,
    val organization: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val privateKey: String? = null,
    val publicKey: String? = null,
    val peerPublicKey: String? = null,
    val interfaceAddressV4: String? = null,
    val interfaceAddressV6: String? = null,
)

interface WarpBootstrapProxyRunner {
    suspend fun <T> withBootstrapProxy(block: suspend () -> T): T
}

@Singleton
class PassthroughWarpBootstrapProxyRunner
    @Inject
    constructor() : WarpBootstrapProxyRunner {
        override suspend fun <T> withBootstrapProxy(block: suspend () -> T): T = block()
    }

interface WarpEndpointScanner {
    suspend fun resolveEndpoint(
        profileId: String,
        networkScopeKey: String,
        provisioned: WarpEndpointCacheEntry?,
    ): WarpEndpointCacheEntry?
}

@Singleton
class DefaultWarpEndpointScanner
    @Inject
    constructor(
        private val endpointStore: WarpEndpointStore,
    ) : WarpEndpointScanner {
        override suspend fun resolveEndpoint(
            profileId: String,
            networkScopeKey: String,
            provisioned: WarpEndpointCacheEntry?,
        ): WarpEndpointCacheEntry? = endpointStore.load(profileId, networkScopeKey) ?: provisioned
    }

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
        private val appSettingsRepository: AppSettingsRepository,
        private val profileStore: WarpProfileStore,
        private val credentialStore: WarpCredentialStore,
        private val endpointStore: WarpEndpointStore,
        private val provisioningClient: WarpProvisioningClient,
        private val bootstrapProxyRunner: WarpBootstrapProxyRunner,
        private val endpointScanner: WarpEndpointScanner,
    ) : WarpEnrollmentOrchestrator {
        private val mutex = Mutex()

        override suspend fun registerConsumerFree(
            displayName: String,
            request: WarpRegisterDeviceRequest,
            profileId: String,
            networkScopeKey: String?,
        ): WarpEnrollmentSnapshot =
            mutex.withLock {
                val normalizedProfileId = profileId.normalizeProfileId(displayName)
                val provisioning =
                    bootstrapProxyRunner.withBootstrapProxy {
                        provisioningClient.register(request)
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
                    provisioning.credentials.copy(
                        profileId = normalizedProfileId,
                        accountId = provisioning.accountId,
                        accountKind = accountKind,
                        displayName = profile.displayName,
                        license = provisioning.license,
                    )
                profileStore.save(profile)
                profileStore.setActiveProfileId(normalizedProfileId)
                credentialStore.save(normalizedProfileId, credentials)
                val endpoint = saveEndpoint(normalizedProfileId, networkScopeKey, provisioning.endpoint)
                activateProfile(profile = profile, scannerMode = WarpScannerModeAutomatic)
                WarpEnrollmentSnapshot(profile = profile, credentials = credentials, endpoint = endpoint)
            }

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

        override suspend fun importZeroTrustProfile(request: WarpZeroTrustImportRequest): WarpEnrollmentSnapshot =
            saveImportedZeroTrustProfile(request)

        override suspend fun completeZeroTrustBrowserEnrollment(
            request: WarpZeroTrustImportRequest,
        ): WarpEnrollmentSnapshot = saveImportedZeroTrustProfile(request)

        override suspend fun refreshActiveProfile(networkScopeKey: String?): WarpEnrollmentSnapshot =
            mutex.withLock {
                val activeProfile = requireActiveProfile()
                check(activeProfile.accountKind != WarpAccountKindZeroTrust) {
                    "Zero Trust profiles require reenrollment instead of consumer refresh"
                }
                val credentials =
                    credentialStore.load(activeProfile.id)
                        ?: error("No WARP credentials saved for profile ${activeProfile.id}")
                val provisioning =
                    bootstrapProxyRunner.withBootstrapProxy {
                        provisioningClient.refresh(credentials)
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
                profileStore.save(refreshedProfile)
                credentialStore.save(activeProfile.id, refreshedCredentials)
                val endpoint =
                    saveEndpoint(
                        profileId = activeProfile.id,
                        networkScopeKey = networkScopeKey,
                        entry =
                            endpointScanner.resolveEndpoint(
                                profileId = activeProfile.id,
                                networkScopeKey = networkScopeKey.orEmpty(),
                                provisioned = provisioning.endpoint,
                            ),
                    )
                activateProfile(profile = refreshedProfile, scannerMode = WarpScannerModeAutomatic)
                WarpEnrollmentSnapshot(
                    profile = refreshedProfile,
                    credentials = refreshedCredentials,
                    endpoint = endpoint,
                )
            }

        override suspend fun resetProfile(profileId: String) {
            mutex.withLock {
                profileStore.remove(profileId)
                credentialStore.clear(profileId)
                endpointStore.clearProfile(profileId)
                val activeProfileId = profileStore.activeProfileId()
                if (activeProfileId == profileId) {
                    profileStore.setActiveProfileId(null)
                    appSettingsRepository.update {
                        setWarpProfileId(DefaultWarpProfileId)
                        setWarpAccountKind(WarpAccountKindConsumerFree)
                        setWarpZeroTrustOrg("")
                        setWarpSetupState(WarpSetupStateNotConfigured)
                        setWarpLastScannerMode(WarpScannerModeAutomatic)
                    }
                }
            }
        }

        private suspend fun saveImportedZeroTrustProfile(request: WarpZeroTrustImportRequest): WarpEnrollmentSnapshot =
            mutex.withLock {
                require(request.displayName.isNotBlank()) { "Zero Trust display name must not be blank" }
                require(request.organization.isNotBlank()) { "Zero Trust organization must not be blank" }
                require(request.deviceId.isNotBlank()) { "Zero Trust device id must not be blank" }
                require(request.accessToken.isNotBlank()) { "Zero Trust access token must not be blank" }
                val profileId = request.profileId.normalizeProfileId(request.displayName)
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
                profileStore.save(profile)
                profileStore.setActiveProfileId(profileId)
                credentialStore.save(profileId, credentials)
                activateProfile(profile = profile, scannerMode = WarpScannerModeManual)
                WarpEnrollmentSnapshot(profile = profile, credentials = credentials, endpoint = null)
            }

        private suspend fun mutateProfile(
            profileId: String,
            transform: suspend (WarpProfile, WarpCredentials) -> Pair<WarpProfile, WarpCredentials>,
        ): WarpEnrollmentSnapshot =
            mutex.withLock {
                val profile = profileStore.load(profileId) ?: error("No WARP profile found for $profileId")
                val credentials = credentialStore.load(profileId) ?: error("No WARP credentials found for $profileId")
                val (updatedProfile, updatedCredentials) = transform(profile, credentials)
                profileStore.save(updatedProfile)
                credentialStore.save(profileId, updatedCredentials)
                if (profileStore.activeProfileId() == profileId) {
                    activateProfile(profile = updatedProfile, scannerMode = WarpScannerModeManual)
                }
                WarpEnrollmentSnapshot(
                    profile = updatedProfile,
                    credentials = updatedCredentials,
                    endpoint = endpointStore.load(profileId, GlobalWarpEndpointScopeKey),
                )
            }

        private suspend fun activateProfile(
            profile: WarpProfile,
            scannerMode: String,
        ) {
            profileStore.setActiveProfileId(profile.id)
            appSettingsRepository.update {
                setWarpProfileId(profile.id)
                setWarpAccountKind(normalizeWarpAccountKind(profile.accountKind))
                setWarpZeroTrustOrg(profile.zeroTrustOrg)
                setWarpSetupState(normalizeWarpSetupState(profile.setupState))
                setWarpLastScannerMode(normalizeWarpScannerMode(scannerMode))
            }
        }

        private suspend fun requireActiveProfile(): WarpProfile {
            val settingsProfileId = appSettingsRepository.snapshot().warpProfileId
            val candidateId = settingsProfileId.ifBlank { profileStore.activeProfileId().orEmpty() }
            check(candidateId.isNotBlank()) { "No active WARP profile configured" }
            return profileStore.load(candidateId) ?: error("No WARP profile found for $candidateId")
        }

        private suspend fun saveEndpoint(
            profileId: String,
            networkScopeKey: String?,
            entry: WarpEndpointCacheEntry?,
        ): WarpEndpointCacheEntry? {
            val normalizedScope = networkScopeKey?.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey
            val normalizedEntry =
                entry?.copy(
                    profileId = profileId,
                    networkScopeKey = normalizedScope,
                ) ?: return null
            endpointStore.save(normalizedEntry)
            return normalizedEntry
        }

        private fun String.normalizeProfileId(displayName: String): String {
            val base = trim().ifBlank { displayName.trim() }.lowercase()
            val sanitized =
                buildString(base.length) {
                    base.forEach { char ->
                        when {
                            char.isLetterOrDigit() -> append(char)
                            char == '-' || char == '_' -> append(char)
                            char.isWhitespace() -> append('-')
                        }
                    }
                }.trim('-')
            return sanitized.ifBlank { "warp-${System.currentTimeMillis()}" }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class WarpEnrollmentModule {
    @Binds
    @Singleton
    abstract fun bindWarpBootstrapProxyRunner(runner: PassthroughWarpBootstrapProxyRunner): WarpBootstrapProxyRunner

    @Binds
    @Singleton
    abstract fun bindWarpEndpointScanner(scanner: DefaultWarpEndpointScanner): WarpEndpointScanner

    @Binds
    @Singleton
    abstract fun bindWarpEnrollmentOrchestrator(
        orchestrator: DefaultWarpEnrollmentOrchestrator,
    ): WarpEnrollmentOrchestrator
}
