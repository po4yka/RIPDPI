package com.poyka.ripdpi.failover

import android.content.Context
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.data.InitialTransportRaceSnapshot
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.subscription.FailoverPolicy
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.seed.SEED_RELAY_PROFILE_ID_PREFIX
import com.poyka.ripdpi.seed.SIMPLE_RELAY_BUNDLE_ASSET_NAME
import com.poyka.ripdpi.seed.SIMPLE_SEED_GROUP_ID
import com.poyka.ripdpi.seed.seedRelayProfileId
import com.poyka.ripdpi.services.EgressRequirements
import com.poyka.ripdpi.services.InitialRelayCandidate
import com.poyka.ripdpi.services.InitialRelayRacePlan
import com.poyka.ripdpi.services.InitialRelayRacePolicy
import com.poyka.ripdpi.services.InitialRelayRaceResult
import com.poyka.ripdpi.services.InitialRelayTransportClass
import com.poyka.ripdpi.services.relayProfileSupportsUdpAssociation
import com.poyka.ripdpi.services.relayTransportCapabilities
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal fun interface SimpleRelayBundleSource {
    fun read(): String?
}

@Singleton
internal class AssetSimpleRelayBundleSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SimpleRelayBundleSource {
        override fun read(): String? =
            try {
                context.assets.open(SIMPLE_RELAY_BUNDLE_ASSET_NAME).use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText().takeIf { it.isNotBlank() }
                }
            } catch (_: IOException) {
                null
            }
    }

@Singleton
internal class SimpleInitialRelayRacePolicy
    @Inject
    constructor(
        private val bundleSource: SimpleRelayBundleSource,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val serviceStateStore: ServiceStateStore,
        private val failoverCoordinator: InitialRaceFailoverCoordinator,
        private val egressHealthCache: SimpleEgressHealthMemory,
    ) : InitialRelayRacePolicy {
        private var pendingCacheContext: PendingCacheContext? = null

        internal suspend fun plan(
            configuredRelayProfileId: String?,
            configuredRelayKind: String?,
            networkScopeKey: String?,
        ): InitialRelayRacePlan? =
            plan(
                configuredRelayProfileId = configuredRelayProfileId,
                configuredRelayKind = configuredRelayKind,
                networkScopeKey = networkScopeKey,
                requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
            )

        override suspend fun plan(
            configuredRelayProfileId: String?,
            configuredRelayKind: String?,
            networkScopeKey: String?,
            requirements: EgressRequirements,
        ): InitialRelayRacePlan? {
            pendingCacheContext = null
            if (
                configuredRelayProfileId.isNullOrBlank() ||
                !configuredRelayProfileId.startsWith(SEED_RELAY_PROFILE_ID_PREFIX) ||
                configuredRelayKind !in SeededRelayKinds ||
                failoverCoordinator.shouldSkipInitialRelayRace()
            ) {
                publishDisabledSnapshot()
                return null
            }

            val imported =
                bundleSource.read()?.let { payload ->
                    SelectorUrltestGroupImport.import(payload, SIMPLE_SEED_GROUP_ID)
                } as? SelectorUrltestImportResult.Success
            val urltest = imported?.failoverPolicy as? FailoverPolicy.Urltest
            val normalizedProbeUrl = urltest?.probeUrl?.normalizeHttpProbeUrl()
            if (imported == null || normalizedProbeUrl == null) {
                publishDisabledSnapshot()
                return null
            }

            val membersByTag = imported.profiles.associateBy(ProxyProfile::displayName)
            val members = imported.memberOrder.mapNotNull(membersByTag::get)
            val occurrencesByKind = mutableMapOf<String, Int>()
            val seededMembers =
                members.map { profile ->
                    val kind = requireNotNull(profile::class.simpleName)
                    val occurrence = occurrencesByKind.getOrDefault(kind, 0)
                    occurrencesByKind[kind] = occurrence + 1
                    profile to seedRelayProfileId(profile, occurrence)
                }
            val configuredMember =
                seededMembers.firstOrNull { (_, profileId) -> profileId == configuredRelayProfileId }
            // The active race compares two distinct transport classes. When a bundle has
            // multiple endpoints in the configured class, preserve the exact selected
            // profile instead of silently substituting the first declared endpoint.
            val realityMember =
                if (configuredRelayKind == RelayKindVlessReality) {
                    configuredMember?.takeIf { (profile, _) -> profile is ProxyProfile.VlessReality }
                } else {
                    seededMembers.firstOrNull { (profile, _) -> profile is ProxyProfile.VlessReality }
                }
            val hysteriaMember =
                if (configuredRelayKind == RelayKindHysteria2) {
                    configuredMember?.takeIf { (profile, _) -> profile is ProxyProfile.Hysteria2 }
                } else {
                    seededMembers.firstOrNull { (profile, _) -> profile is ProxyProfile.Hysteria2 }
                }
            val hysteria = hysteriaMember?.first as? ProxyProfile.Hysteria2
            val realityCandidate =
                realityMember?.let { (_, profileId) ->
                    val stored = relayProfileStore.load(profileId)
                    val compatible =
                        stored?.kind == RelayKindVlessReality &&
                            relayTransportCapabilities(RelayKindVlessReality)?.satisfies(requirements) == true &&
                            (
                                !requirements.udpAssociate ||
                                    relayProfileSupportsUdpAssociation(
                                        kindId = stored.kind,
                                        udpEnabled = stored.udpEnabled,
                                        vlessTransport = stored.vlessTransport,
                                        vlessFlow = stored.vlessFlow,
                                    )
                            )
                    if (compatible) {
                        InitialRelayCandidate(
                            transportClass = InitialRelayTransportClass.TlsMimicry,
                            profileId = profileId,
                            relayKind = RelayKindVlessReality,
                        )
                    } else {
                        null
                    }
                }
            val hysteriaCandidate =
                hysteriaMember?.let { (_, profileId) ->
                    val stored = relayProfileStore.load(profileId)
                    val credentials = relayCredentialStore.load(profileId)
                    val compatible =
                        hysteria != null &&
                            stored?.kind == RelayKindHysteria2 &&
                            relayTransportCapabilities(RelayKindHysteria2)?.satisfies(requirements) == true &&
                            (
                                !requirements.udpAssociate ||
                                    relayProfileSupportsUdpAssociation(
                                        kindId = stored.kind,
                                        udpEnabled = stored.udpEnabled,
                                        vlessTransport = stored.vlessTransport,
                                        vlessFlow = stored.vlessFlow,
                                    )
                            ) &&
                            !credentials?.hysteriaPassword.isNullOrBlank() &&
                            (
                                hysteria.obfsPassword.isNullOrBlank() ||
                                    !credentials.hysteriaSalamanderKey.isNullOrBlank()
                            )
                    if (compatible) {
                        InitialRelayCandidate(
                            transportClass = InitialRelayTransportClass.UdpObfuscation,
                            profileId = profileId,
                            relayKind = RelayKindHysteria2,
                        )
                    } else {
                        null
                    }
                }
            val proof = EgressProof.from(requirements)
            val candidates =
                listOfNotNull(realityCandidate, hysteriaCandidate).filterNot { candidate ->
                    egressHealthCache.isCoolingDown(networkScopeKey, proof, candidate)
                }
            if (candidates.isEmpty() || (!requirements.udpAssociate && candidates.size != 2)) {
                publishDisabledSnapshot()
                return null
            }
            val signature = candidateSignature(normalizedProbeUrl, requirements, candidates)
            val cachedWinner =
                networkScopeKey
                    ?.takeIf { candidates.size > 1 }
                    ?.let { egressHealthCache.readWinner(it, signature, proof) }
                    ?.takeIf { winner ->
                        candidates.any { it.profileId == winner }
                    }
            pendingCacheContext =
                networkScopeKey?.let {
                    PendingCacheContext(
                        networkScopeKey = it,
                        signature = signature,
                        proof = proof,
                    )
                }
            return InitialRelayRacePlan(
                probeUrl = normalizedProbeUrl,
                candidates = candidates,
                requirements = requirements,
                cachedFallbackProfileId = cachedWinner,
            )
        }

        override fun onStateChanged(state: InitialTransportRaceSnapshot) {
            serviceStateStore.updateTelemetry(
                serviceStateStore.telemetry.value.copy(initialTransportRaceSnapshot = state),
            )
        }

        override fun onSelected(result: InitialRelayRaceResult) {
            failoverCoordinator.recordInitialRelaySelection(
                profileId = result.selectedCandidate.profileId,
                relayKind = result.selectedCandidate.relayKind,
            )
            if (!result.usedCachedFallback) {
                pendingCacheContext?.let { cacheContext ->
                    egressHealthCache.writeWinner(
                        networkScopeKey = cacheContext.networkScopeKey,
                        signature = cacheContext.signature,
                        proof = cacheContext.proof,
                        profileId = result.selectedCandidate.profileId,
                    )
                }
            }
            pendingCacheContext = null
        }

        private fun publishDisabledSnapshot() {
            onStateChanged(InitialTransportRaceSnapshot(state = RaceStateDisabled))
        }

        private fun candidateSignature(
            normalizedProbeUrl: String,
            requirements: EgressRequirements,
            candidates: List<InitialRelayCandidate>,
        ): String =
            sha256(
                buildString {
                    append(BuildConfig.VERSION_NAME)
                    append('|')
                    append(normalizedProbeUrl)
                    append('|')
                    append(requirements.tcpConnect)
                    append(':')
                    append(requirements.udpAssociate)
                    candidates.sortedBy(InitialRelayCandidate::profileId).forEach { candidate ->
                        append('|')
                        append(candidate.profileId)
                        append(':')
                        append(candidate.relayKind)
                    }
                },
            )

        private data class PendingCacheContext(
            val networkScopeKey: String,
            val signature: String,
            val proof: EgressProof,
        )

        private companion object {
            const val RaceStateDisabled = "disabled"
            val SeededRelayKinds = setOf(RelayKindVlessReality, RelayKindHysteria2)
        }
    }

private fun String.normalizeHttpProbeUrl(): String? =
    runCatching {
        val parsed = URI(trim()).normalize()
        val scheme = parsed.scheme?.lowercase()
        val host = parsed.host?.lowercase()
        require(scheme == "http" || scheme == "https")
        require(!host.isNullOrBlank())
        require(parsed.userInfo == null)
        URI(
            scheme,
            null,
            host,
            parsed.port,
            parsed.path.ifEmpty { "/" },
            parsed.query,
            null,
        ).toASCIIString()
    }.getOrNull()

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SimpleInitialRelayRaceModule {
    @Binds
    abstract fun bindBundleSource(source: AssetSimpleRelayBundleSource): SimpleRelayBundleSource

    // No startup race: VLESS Reality always gets the first attempt. The session watcher owns the
    // single Hysteria2 retry only after VLESS fails before readiness.
}
