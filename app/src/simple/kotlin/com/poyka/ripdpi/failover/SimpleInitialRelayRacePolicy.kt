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
import com.poyka.ripdpi.services.InitialRelayCandidate
import com.poyka.ripdpi.services.InitialRelayRacePlan
import com.poyka.ripdpi.services.InitialRelayRacePolicy
import com.poyka.ripdpi.services.InitialRelayRaceResult
import com.poyka.ripdpi.services.InitialRelayTransportClass
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
        @ApplicationContext context: Context,
        private val bundleSource: SimpleRelayBundleSource,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val serviceStateStore: ServiceStateStore,
        private val failoverCoordinator: InitialRaceFailoverCoordinator,
        private val clock: FailoverClock,
    ) : InitialRelayRacePolicy {
        private val preferences = context.getSharedPreferences(CachePreferencesName, Context.MODE_PRIVATE)
        private var pendingCacheContext: PendingCacheContext? = null

        override suspend fun plan(
            configuredRelayProfileId: String?,
            configuredRelayKind: String?,
            networkScopeKey: String?,
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
            val reality = realityMember?.first as? ProxyProfile.VlessReality
            val hysteria = hysteriaMember?.first as? ProxyProfile.Hysteria2
            if (reality == null || hysteria == null) {
                publishDisabledSnapshot()
                return null
            }
            val realityProfileId = requireNotNull(realityMember).second
            val hysteriaProfileId = requireNotNull(hysteriaMember).second
            val storedReality = relayProfileStore.load(realityProfileId)
            val storedHysteria = relayProfileStore.load(hysteriaProfileId)
            val hysteriaCredentials = relayCredentialStore.load(hysteriaProfileId)
            val hysteriaPassword = hysteriaCredentials?.hysteriaPassword
            val hysteriaSalamanderKey = hysteriaCredentials?.hysteriaSalamanderKey
            if (
                storedReality?.kind != RelayKindVlessReality ||
                storedHysteria?.kind != RelayKindHysteria2 ||
                hysteriaPassword.isNullOrBlank() ||
                (!hysteria.obfsPassword.isNullOrBlank() && hysteriaSalamanderKey.isNullOrBlank())
            ) {
                publishDisabledSnapshot()
                return null
            }

            val candidates =
                listOf(
                    InitialRelayCandidate(
                        transportClass = InitialRelayTransportClass.TlsMimicry,
                        profileId = realityProfileId,
                        relayKind = RelayKindVlessReality,
                    ),
                    InitialRelayCandidate(
                        transportClass = InitialRelayTransportClass.UdpObfuscation,
                        profileId = hysteriaProfileId,
                        relayKind = RelayKindHysteria2,
                    ),
                )
            val signature = candidateSignature(normalizedProbeUrl, candidates)
            val cacheKey = networkScopeKey?.takeIf(String::isNotBlank)?.let { cacheKey(it, signature) }
            val cachedWinner =
                cacheKey?.let(::readUnexpiredWinner)?.takeIf { winner ->
                    candidates.any { it.profileId == winner }
                }
            pendingCacheContext = cacheKey?.let { PendingCacheContext(it) }
            return InitialRelayRacePlan(
                probeUrl = normalizedProbeUrl,
                candidates = candidates,
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
                    preferences
                        .edit()
                        .putString(
                            cacheContext.key,
                            "${clock.nowMillis()}|${result.selectedCandidate.profileId}",
                        ).apply()
                }
            }
            pendingCacheContext = null
        }

        private fun publishDisabledSnapshot() {
            onStateChanged(InitialTransportRaceSnapshot(state = RaceStateDisabled))
        }

        private fun readUnexpiredWinner(key: String): String? {
            val stored = preferences.getString(key, null) ?: return null
            val separator = stored.indexOf('|')
            if (separator <= 0 || separator == stored.lastIndex) return null
            val confirmedAt = stored.substring(0, separator).toLongOrNull() ?: return null
            if (clock.nowMillis() - confirmedAt !in 0..CacheTtlMillis) {
                preferences.edit().remove(key).apply()
                return null
            }
            return stored.substring(separator + 1)
        }

        private fun candidateSignature(
            normalizedProbeUrl: String,
            candidates: List<InitialRelayCandidate>,
        ): String =
            sha256(
                buildString {
                    append(BuildConfig.VERSION_NAME)
                    append('|')
                    append(normalizedProbeUrl)
                    candidates.sortedBy(InitialRelayCandidate::profileId).forEach { candidate ->
                        append('|')
                        append(candidate.profileId)
                        append(':')
                        append(candidate.relayKind)
                    }
                },
            )

        private fun cacheKey(
            networkScopeKey: String,
            signature: String,
        ): String = "winner.${sha256(networkScopeKey)}.$signature"

        private data class PendingCacheContext(
            val key: String,
        )

        private companion object {
            const val CachePreferencesName = "simple_initial_relay_race"
            const val CacheTtlMillis = 24L * 60L * 60L * 1_000L
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

    @Binds
    abstract fun bindInitialRelayRacePolicy(policy: SimpleInitialRelayRacePolicy): InitialRelayRacePolicy
}
