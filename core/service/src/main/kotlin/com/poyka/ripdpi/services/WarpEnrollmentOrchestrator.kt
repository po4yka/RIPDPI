@file:Suppress("ReturnCount", "MagicNumber")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiHostsConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.core.RipDpiWarpNativeBindings
import com.poyka.ripdpi.core.WarpEndpointProbeNativeRequest
import com.poyka.ripdpi.core.WarpEndpointProbeNativeResult
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.BuiltInWarpControlPlaneHosts
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.DefaultWarpScannerMaxRttMs
import com.poyka.ripdpi.data.DefaultWarpScannerParallelism
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

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

data class WarpBootstrapProxyConfig(
    val host: String,
    val port: Int,
) {
    fun asOkHttpProxy(): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
}

interface WarpBootstrapProxyRunner {
    suspend fun <T> withBootstrapProxy(block: suspend (WarpBootstrapProxyConfig?) -> T): T
}

@Singleton
class PassthroughWarpBootstrapProxyRunner
    @Inject
    constructor() : WarpBootstrapProxyRunner {
        override suspend fun <T> withBootstrapProxy(block: suspend (WarpBootstrapProxyConfig?) -> T): T = block(null)
    }

@Singleton
internal class ManagedWarpBootstrapProxyRunner
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val proxyRuntimeSupervisorFactory: ProxyRuntimeSupervisorFactory,
        private val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        @param:ApplicationIoScope private val scope: CoroutineScope,
    ) : WarpBootstrapProxyRunner {
        override suspend fun <T> withBootstrapProxy(block: suspend (WarpBootstrapProxyConfig?) -> T): T {
            val bootstrapPort = reserveLoopbackPort()
            val basePreferences = RipDpiProxyUIPreferences.fromSettings(appSettingsRepository.snapshot())
            val bootstrapPreferences =
                RipDpiProxyUIPreferences(
                    protocols = basePreferences.protocols,
                    parserEvasions = basePreferences.parserEvasions,
                    adaptiveFallback = basePreferences.adaptiveFallback,
                    wsTunnel = basePreferences.wsTunnel,
                    listen = basePreferences.listen.copy(ip = LoopbackHost, port = bootstrapPort),
                    chains = basePreferences.chains,
                    fakePackets = basePreferences.fakePackets,
                    quic = basePreferences.quic,
                    hosts =
                        RipDpiHostsConfig(
                            mode = RipDpiHostsConfig.Mode.Whitelist,
                            entries = BuiltInWarpControlPlaneHosts.joinToString(separator = "\n"),
                        ),
                    relay = RipDpiRelayConfig(enabled = false),
                    warp = RipDpiWarpConfig(enabled = false),
                    hostAutolearn = basePreferences.hostAutolearn,
                    nativeLogLevel = basePreferences.nativeLogLevel,
                    runtimeContext = basePreferences.runtimeContext,
                    logContext = basePreferences.logContext,
                    rootMode = basePreferences.rootMode,
                    rootHelperSocketPath = basePreferences.rootHelperSocketPath,
                )
            val proxyRuntimeSupervisor =
                proxyRuntimeSupervisorFactory.create(
                    scope = scope,
                    dispatcher = Dispatchers.IO,
                    networkSnapshotProvider = networkSnapshotProvider,
                )
            proxyRuntimeSupervisor.start(preferences = bootstrapPreferences, onUnexpectedExit = {})
            return try {
                block(WarpBootstrapProxyConfig(host = LoopbackHost, port = bootstrapPort))
            } finally {
                proxyRuntimeSupervisor.stop()
            }
        }

        private fun reserveLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

        private companion object {
            private const val LoopbackHost = "127.0.0.1"
        }
    }

interface WarpEndpointScanner {
    suspend fun resolveEndpoint(
        profileId: String,
        networkScopeKey: String,
        provisioned: WarpEndpointCacheEntry?,
    ): WarpEndpointCacheEntry?
}

interface WarpEndpointProbe {
    suspend fun probe(
        candidate: WarpEndpointCacheEntry,
        timeoutMillis: Int,
    ): WarpEndpointCacheEntry?
}

@Singleton
class DefaultWarpEndpointProbe
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val credentialStore: WarpCredentialStore,
        private val nativeBindings: RipDpiWarpNativeBindings,
    ) : WarpEndpointProbe {
        override suspend fun probe(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? =
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { probeBlocking(candidate, timeoutMillis.coerceAtLeast(250)) }.getOrNull()
            }

        private fun probeBlocking(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? {
            probeNative(candidate, timeoutMillis)?.let { return it }
            return probeFallbackUdp(candidate, timeoutMillis)
        }

        private fun probeNative(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? {
            val credentials = runBlocking { credentialStore.load(candidate.profileId) } ?: return null
            val privateKey = credentials.privateKey?.takeIf(String::isNotBlank) ?: return null
            val peerPublicKey = credentials.peerPublicKey?.takeIf(String::isNotBlank) ?: return null
            val settings = runBlocking { appSettingsRepository.snapshot() }
            val request =
                WarpEndpointProbeNativeRequest(
                    endpoint = candidate.toResolvedEndpoint(),
                    privateKey = privateKey,
                    peerPublicKey = peerPublicKey,
                    clientId = credentials.clientId,
                    amnezia =
                        com.poyka.ripdpi.core.RipDpiWarpAmneziaConfig(
                            enabled = settings.warpAmneziaEnabled,
                            jc = settings.warpAmneziaJc,
                            jmin = settings.warpAmneziaJmin,
                            jmax = settings.warpAmneziaJmax,
                            h1 = settings.warpAmneziaH1,
                            h2 = settings.warpAmneziaH2,
                            h3 = settings.warpAmneziaH3,
                            h4 = settings.warpAmneziaH4,
                            s1 = settings.warpAmneziaS1,
                            s2 = settings.warpAmneziaS2,
                            s3 = settings.warpAmneziaS3,
                            s4 = settings.warpAmneziaS4,
                        ),
                    timeoutMs = timeoutMillis.toLong(),
                )
            val resultJson = nativeBindings.probeEndpoint(WarpProbeJson.encodeToString(request)) ?: return null
            val result = WarpProbeJson.decodeFromString<WarpEndpointProbeNativeResult>(resultJson)
            return candidate.copy(
                host =
                    result.host
                        .ifBlank {
                            candidate.host ?: ""
                        }.ifBlank { candidate.ipv4 ?: candidate.ipv6.orEmpty() },
                ipv4 = result.ipv4 ?: candidate.ipv4,
                ipv6 = result.ipv6 ?: candidate.ipv6,
                port = result.port,
                source = "scanner_native",
                rttMs = result.rttMs,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }

        private fun probeFallbackUdp(
            candidate: WarpEndpointCacheEntry,
            timeoutMillis: Int,
        ): WarpEndpointCacheEntry? {
            val address = resolveSocketAddress(candidate) ?: return null
            val startedAtNanos = System.nanoTime()
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMillis
                socket.connect(address)
                val payload = byteArrayOf(0x01, 0x03, 0x03, 0x07)
                socket.send(DatagramPacket(payload, payload.size))
                try {
                    val response = DatagramPacket(ByteArray(64), 64)
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    // WARP UDP endpoints typically stay silent; a clean send is still a usable signal.
                }
            }
            val resolvedAddress = address.address
            val elapsedMillis = max(1L, (System.nanoTime() - startedAtNanos) / 1_000_000L)
            return candidate.copy(
                host = candidate.host?.ifBlank { address.hostString } ?: address.hostString,
                ipv4 = candidate.ipv4 ?: (resolvedAddress as? Inet4Address)?.hostAddress,
                ipv6 = candidate.ipv6 ?: (resolvedAddress as? Inet6Address)?.hostAddress,
                source = "scanner",
                rttMs = elapsedMillis,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }

        private fun WarpEndpointCacheEntry.toResolvedEndpoint() =
            com.poyka.ripdpi.core.ResolvedRipDpiWarpEndpoint(
                host = host?.ifBlank { ipv4 ?: ipv6.orEmpty() } ?: ipv4 ?: ipv6.orEmpty(),
                ipv4 = ipv4,
                ipv6 = ipv6,
                port = port,
                source = source,
            )

        private fun resolveSocketAddress(candidate: WarpEndpointCacheEntry): InetSocketAddress? {
            val port = candidate.port.takeIf { it > 0 } ?: return null
            candidate.ipv4?.takeIf(String::isNotBlank)?.let { value ->
                return InetSocketAddress(value, port)
            }
            candidate.ipv6?.takeIf(String::isNotBlank)?.let { value ->
                return InetSocketAddress(value, port)
            }
            val host = candidate.host?.takeIf(String::isNotBlank) ?: return null
            val resolved =
                runCatching { InetAddress.getAllByName(host).firstOrNull() }.getOrNull()
                    ?: return null
            return InetSocketAddress(resolved, port)
        }

        private companion object {
            val WarpProbeJson =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
        }
    }

@Singleton
class DefaultWarpEndpointScanner
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val endpointStore: WarpEndpointStore,
        private val endpointProbe: WarpEndpointProbe,
    ) : WarpEndpointScanner {
        override suspend fun resolveEndpoint(
            profileId: String,
            networkScopeKey: String,
            provisioned: WarpEndpointCacheEntry?,
        ): WarpEndpointCacheEntry? {
            val normalizedScope = networkScopeKey.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey
            val now = System.currentTimeMillis()
            val settings = appSettingsRepository.snapshot()
            val scannerEnabled = settings.warpScannerEnabled
            val parallelism =
                settings.warpScannerParallelism
                    .takeIf { it > 0 }
                    ?: DefaultWarpScannerParallelism
            val timeoutMillis =
                settings.warpScannerMaxRttMs
                    .takeIf { it > 0 }
                    ?: DefaultWarpScannerMaxRttMs

            endpointStore.load(profileId, normalizedScope)?.let { cached ->
                if (cached.isFreshWarpEndpoint(now)) {
                    return cached.copy(profileId = profileId, networkScopeKey = normalizedScope)
                }
                probeCachedWarpEntry(
                    endpointProbe = endpointProbe,
                    endpointStore = endpointStore,
                    profileId = profileId,
                    networkScopeKey = normalizedScope,
                    entry = cached,
                    timeoutMillis = timeoutMillis,
                )?.let { return it }
            }

            endpointStore.load(profileId, GlobalWarpEndpointScopeKey)?.let { cached ->
                probeCachedWarpEntry(
                    endpointProbe = endpointProbe,
                    endpointStore = endpointStore,
                    profileId = profileId,
                    networkScopeKey = GlobalWarpEndpointScopeKey,
                    entry = cached,
                    timeoutMillis = timeoutMillis,
                )?.let { global ->
                    return persistWarpBestCandidate(
                        endpointStore = endpointStore,
                        profileId = profileId,
                        networkScopeKey = normalizedScope,
                        candidate = global,
                    )
                }
            }

            if (scannerEnabled) {
                val bestCandidate =
                    scanWarpCandidatePool(
                        endpointProbe = endpointProbe,
                        candidates = buildWarpCandidatePool(endpointStore, profileId, provisioned),
                        timeoutMillis = timeoutMillis,
                        parallelism = parallelism,
                    )
                if (bestCandidate != null) {
                    return persistWarpBestCandidate(
                        endpointStore = endpointStore,
                        profileId = profileId,
                        networkScopeKey = normalizedScope,
                        candidate = bestCandidate,
                    )
                }
            }

            return provisioned?.let { fallback ->
                persistWarpBestCandidate(
                    endpointStore = endpointStore,
                    profileId = profileId,
                    networkScopeKey = normalizedScope,
                    candidate = fallback,
                )
            }
        }
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
                val endpoint =
                    endpointScanner.resolveEndpoint(
                        profileId = normalizedProfileId,
                        networkScopeKey = networkScopeKey.orEmpty(),
                        provisioned = provisioning.endpoint,
                    )
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
                        markProfileNeedsAttention(activeProfile)
                        throw error
                    } catch (error: WarpProvisioningException.MalformedResponse) {
                        markProfileNeedsAttention(activeProfile)
                        throw error
                    } catch (error: IOException) {
                        if (error.message.orEmpty().contains("HTTP 401") ||
                            error.message.orEmpty().contains("HTTP 403")
                        ) {
                            markProfileNeedsAttention(activeProfile)
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
                profileStore.save(refreshedProfile)
                credentialStore.save(activeProfile.id, refreshedCredentials)
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

        private suspend fun markProfileNeedsAttention(profile: WarpProfile) {
            val updatedProfile = profile.copy(setupState = WarpSetupStateNeedsAttention)
            profileStore.save(updatedProfile)
            if (profileStore.activeProfileId() == profile.id ||
                appSettingsRepository.snapshot().warpProfileId == profile.id
            ) {
                activateProfile(profile = updatedProfile, scannerMode = updatedProfile.lastScannerModeOrAutomatic())
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class WarpEnrollmentModule {
    @Binds
    @Singleton
    internal abstract fun bindWarpBootstrapProxyRunner(
        runner: ManagedWarpBootstrapProxyRunner,
    ): WarpBootstrapProxyRunner

    @Binds
    @Singleton
    abstract fun bindWarpEndpointScanner(scanner: DefaultWarpEndpointScanner): WarpEndpointScanner

    @Binds
    @Singleton
    abstract fun bindWarpEndpointProbe(probe: DefaultWarpEndpointProbe): WarpEndpointProbe

    @Binds
    @Singleton
    abstract fun bindWarpEnrollmentOrchestrator(
        orchestrator: DefaultWarpEnrollmentOrchestrator,
    ): WarpEnrollmentOrchestrator
}
