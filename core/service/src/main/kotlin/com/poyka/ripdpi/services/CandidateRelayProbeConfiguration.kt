package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.ownedRelayQuicMigrationConfig
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/** Captures relevant runtime inputs and builds a transient candidate with the canonical configuration builder. */
@Singleton
internal class CandidateRelayProbeConfiguration
    @Inject
    constructor(
        private val resolver: DefaultUpstreamRelayRuntimeConfigResolver,
        private val stateStore: ServiceStateStore,
        private val settings: AppSettingsRepository,
        private val experiments: RuntimeExperimentSelectionProvider,
    ) {
        suspend fun capture(): CandidateRelayProbeEnvironment {
            val status = stateStore.status.value
            val snapshot = settings.snapshot()
            val migration = snapshot.ownedRelayQuicMigrationConfig()
            return CandidateRelayProbeEnvironment(
                status.first != AppStatus.Halted && status.second == Mode.VPN,
                normalizeTlsFingerprintProfile(snapshot.tlsFingerprintProfile),
                Collections.unmodifiableMap(experiments.current().featureFlags.toMap()),
                migration.bindLowPort,
                migration.migrateAfterHandshake,
            )
        }

        suspend fun prepare(
            profile: RelayProfileRecord,
            credentials: RelayCredentialRecord,
            environment: CandidateRelayProbeEnvironment,
        ): ResolvedRipDpiRelayConfig =
            resolver.resolveTransient(
                profile,
                credentials,
                OwnedRelayQuicMigrationConfig(environment.quicBindLowPort, environment.quicMigrateAfterHandshake),
                environment.tlsProfile,
                environment.featureFlags,
            )
    }
