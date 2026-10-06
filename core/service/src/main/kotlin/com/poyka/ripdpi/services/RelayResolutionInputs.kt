package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.ownedRelayQuicMigrationConfig
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.proto.AppSettings
import java.util.Collections

/** Frozen resolver inputs: native resolution never adopts live TLS, QUIC or experiment settings after claim. */
internal class RelayResolutionInputs(
    val tlsProfile: String,
    featureFlags: Map<String, Boolean>,
    val quic: OwnedRelayQuicMigrationConfig,
) {
    val featureFlags: Map<String, Boolean> = Collections.unmodifiableMap(featureFlags.toSortedMap())

    fun identityMaterial(): List<String> =
        listOf(
            tlsProfile,
            quic.bindLowPort.toString(),
            quic.migrateAfterHandshake.toString(),
        ) + featureFlags.flatMap { (key, enabled) -> listOf(key, enabled.toString()) }

    override fun toString(): String = "RelayResolutionInputs([REDACTED])"

    companion object {
        fun capture(
            settings: AppSettings,
            experiments: RuntimeExperimentSelection,
        ): RelayResolutionInputs =
            RelayResolutionInputs(
                normalizeTlsFingerprintProfile(settings.tlsFingerprintProfile),
                experiments.featureFlags,
                settings.ownedRelayQuicMigrationConfig(),
            )
    }
}
