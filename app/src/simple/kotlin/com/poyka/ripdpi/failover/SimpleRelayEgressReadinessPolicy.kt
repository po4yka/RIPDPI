package com.poyka.ripdpi.failover

import android.content.Context
import com.poyka.ripdpi.data.InitialTransportRaceSnapshot
import com.poyka.ripdpi.data.InitialTransportSelectionException
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.relayProfileSupportsUdpAssociation
import com.poyka.ripdpi.data.subscription.FailoverPolicy
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.seed.SEED_RELAY_PROFILE_ID_PREFIX
import com.poyka.ripdpi.seed.SIMPLE_RELAY_BUNDLE_ASSET_NAME
import com.poyka.ripdpi.seed.SIMPLE_SEED_AWG_PROFILE_ID
import com.poyka.ripdpi.seed.SIMPLE_SEED_GROUP_ID
import com.poyka.ripdpi.services.AmneziaWgEgressKind
import com.poyka.ripdpi.services.EgressRequirements
import com.poyka.ripdpi.services.InitialRelayCandidate
import com.poyka.ripdpi.services.InitialRelayRacePlan
import com.poyka.ripdpi.services.InitialRelayRacePolicy
import com.poyka.ripdpi.services.InitialRelayRaceResult
import com.poyka.ripdpi.services.InitialRelayTransportClass
import com.poyka.ripdpi.services.RelayHealthScope
import com.poyka.ripdpi.services.RelayProbePlan
import com.poyka.ripdpi.services.RelayTargetCategory
import com.poyka.ripdpi.services.relayTransportCapabilities
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.net.URI
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

/**
 * Gates the simple flavor's connected state on real egress through the configured relay or AWG.
 *
 * Relay readiness proves only a bound listener and AWG readiness proves only a peer handshake.
 * A one-candidate preflight requires an HTTP request through the selected local SOCKS listener
 * before the VPN tunnel can be published as running.
 */
@Singleton
internal class SimpleRelayEgressReadinessPolicy
    @Inject
    constructor(
        private val bundleSource: SimpleRelayBundleSource,
        private val relayProfileStore: RelayProfileStore,
        private val serviceStateStore: ServiceStateStore,
    ) : InitialRelayRacePolicy {
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
            val profileId = configuredRelayProfileId
            val relayKind = configuredRelayKind
            val seededAwg =
                relayKind == AmneziaWgEgressKind &&
                    profileId?.startsWith(SIMPLE_SEED_AWG_PROFILE_ID) == true
            val seededRelay =
                relayKind in SeededRelayKinds &&
                    profileId?.startsWith(SEED_RELAY_PROFILE_ID_PREFIX) == true
            if (
                profileId.isNullOrBlank() ||
                (!seededRelay && !seededAwg)
            ) {
                publishDisabledSnapshot()
                return null
            }
            val activeRelayKind = requireNotNull(relayKind)
            val sessionRequirements =
                if (seededRelay) {
                    EgressRequirements(tcpConnect = true, udpAssociate = false)
                } else {
                    requirements
                }

            if (!seededAwg) {
                val stored =
                    relayProfileStore.load(profileId)
                        ?: rejectReadiness("Configured embedded relay profile is unavailable")
                if (
                    stored.kind != activeRelayKind ||
                    (
                        activeRelayKind == RelayKindVless &&
                            stored.vlessTransport != RelayVlessTransportXhttp
                    ) ||
                    relayTransportCapabilities(activeRelayKind)?.satisfies(sessionRequirements) != true ||
                    (
                        sessionRequirements.udpAssociate &&
                            !relayProfileSupportsUdpAssociation(
                                kindId = stored.kind,
                                udpEnabled = stored.udpEnabled,
                                vlessTransport = stored.vlessTransport,
                                vlessFlow = stored.vlessFlow,
                            )
                    )
                ) {
                    rejectReadiness("Configured embedded relay cannot satisfy the active egress probe")
                }
            }

            val imported =
                bundleSource.read()?.let { payload ->
                    SelectorUrltestGroupImport.import(payload, SIMPLE_SEED_GROUP_ID)
                } as? SelectorUrltestImportResult.Success
            check(imported?.failoverPolicy !is FailoverPolicy.Payload) {
                "Embedded Simple configuration does not support payload failover policy"
            }
            val probeUrl =
                (imported?.failoverPolicy as? FailoverPolicy.Urltest)
                    ?.probeUrl
                    ?.normalizeHttpProbeUrl()
            val transportClass =
                when (activeRelayKind) {
                    RelayKindVlessReality,
                    RelayKindVless,
                    -> InitialRelayTransportClass.TlsMimicry

                    RelayKindHysteria2 -> InitialRelayTransportClass.UdpObfuscation

                    AmneziaWgEgressKind -> InitialRelayTransportClass.UdpObfuscation

                    else -> error("unreachable")
                }
            return InitialRelayRacePlan(
                probePlan =
                    RelayProbePlan(
                        targetUrl = probeUrl,
                        targetCategory =
                            if (probeUrl == null) {
                                RelayTargetCategory.Unavailable
                            } else {
                                RelayTargetCategory.ApplicationHttp
                            },
                        requirements =
                            EgressRequirements(
                                tcpConnect = true,
                                udpAssociate = false,
                            ),
                    ),
                candidates =
                    listOf(
                        InitialRelayCandidate(
                            transportClass = transportClass,
                            profileId = profileId,
                            relayKind = activeRelayKind,
                        ),
                    ),
                requirements = sessionRequirements,
                healthScope =
                    RelayHealthScope(
                        persistentNetworkHash = networkScopeKey,
                        sessionGeneration = serviceStateStore.telemetry.value.serviceStartedAt ?: 0L,
                    ),
            )
        }

        override fun onStateChanged(state: InitialTransportRaceSnapshot) {
            serviceStateStore.updateTelemetry(
                serviceStateStore.telemetry.value.copy(initialTransportRaceSnapshot = state),
            )
        }

        override fun onSelected(result: InitialRelayRaceResult) = Unit

        private fun publishDisabledSnapshot() {
            onStateChanged(InitialTransportRaceSnapshot(state = RaceStateDisabled))
        }

        private fun rejectReadiness(message: String): Nothing {
            publishDisabledSnapshot()
            throw InitialTransportSelectionException(message)
        }

        private companion object {
            const val RaceStateDisabled = "disabled"
            val SeededRelayKinds = setOf(RelayKindVlessReality, RelayKindVless, RelayKindHysteria2)
        }
    }

internal fun String.normalizeHttpProbeUrl(): String? =
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

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SimpleInitialRelayRaceModule {
    @Binds
    abstract fun bindBundleSource(source: AssetSimpleRelayBundleSource): SimpleRelayBundleSource

    @Binds
    abstract fun bindInitialRelayRacePolicy(policy: SimpleRelayEgressReadinessPolicy): InitialRelayRacePolicy
}
