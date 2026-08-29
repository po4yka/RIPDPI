package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.awgConfigOrNull
import com.poyka.ripdpi.core.isUdpAssociateEnabled
import com.poyka.ripdpi.core.ownedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.data.Mode
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

interface ServiceStartLocalNetworkPreflight {
    suspend fun requireAccess(mode: Mode)
}

@Singleton
internal class DefaultServiceStartLocalNetworkPreflight internal constructor(
    private val resolvePolicy: suspend (Mode) -> ConnectionPolicyResolution,
    private val resolveRelay: suspend (RipDpiRelayConfig, OwnedRelayQuicMigrationConfig) -> Unit,
    private val planInitialRace:
        suspend (Mode, ConnectionPolicyResolution, RipDpiRelayConfig) -> InitialRelayRacePlan?,
) : ServiceStartLocalNetworkPreflight {
    @Inject
    constructor(
        connectionPolicyResolver: ConnectionPolicyResolver,
        relayConfigResolver: PermissionCheckedRelayConfigResolver,
        initialRelayRacePolicy: Optional<InitialRelayRacePolicy>,
    ) : this(
        resolvePolicy = connectionPolicyResolver::resolve,
        resolveRelay = { relay, migration ->
            relayConfigResolver.resolveWithLocalNetworkDependency(relay, migration)
        },
        planInitialRace = { mode, resolution, relay ->
            if (mode == Mode.VPN) {
                val requirements =
                    EgressRequirements(
                        tcpConnect = true,
                        udpAssociate = resolution.proxyPreferences.isUdpAssociateEnabled(),
                    )
                initialRelayRacePolicy.orElse(null)?.plan(
                    configuredRelayProfileId = relay.profileId,
                    configuredRelayKind = relay.kind,
                    networkScopeKey = resolution.networkScopeKey,
                    requirements = requirements,
                )
            } else {
                null
            }
        },
    )

    override suspend fun requireAccess(mode: Mode) {
        val resolution = resolvePolicy(mode)
        val preferences = resolution.proxyPreferences
        val configuredRelay = preferences.relayConfigOrNull().takeIf { preferences.awgConfigOrNull() == null } ?: return
        val migration = preferences.ownedRelayQuicMigrationConfig()
        val racePlan = planInitialRace(mode, resolution, configuredRelay)
        val relayConfigs =
            racePlan?.candidates?.map { candidate ->
                RipDpiRelayConfig(
                    enabled = true,
                    kind = candidate.relayKind,
                    profileId = candidate.profileId,
                )
            } ?: listOf(configuredRelay)
        relayConfigs.forEach { relay -> resolveRelay(relay, migration) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ServiceStartLocalNetworkPreflightModule {
    @Binds
    @Singleton
    abstract fun bindServiceStartLocalNetworkPreflight(
        preflight: DefaultServiceStartLocalNetworkPreflight,
    ): ServiceStartLocalNetworkPreflight
}
