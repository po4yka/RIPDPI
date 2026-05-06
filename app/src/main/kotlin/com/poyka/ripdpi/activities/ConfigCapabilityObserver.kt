package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigCapabilityObserver
    @Inject
    constructor(
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val serverCapabilityStore: ServerCapabilityStore,
    ) {
        suspend fun relayCapabilitiesForCurrentNetwork(): List<ServerCapabilityRecord> =
            runCatching {
                networkFingerprintProvider
                    .capture()
                    ?.let { fingerprint ->
                        serverCapabilityStore.relayCapabilitiesForFingerprint(fingerprint.scopeKey())
                    }.orEmpty()
            }.getOrDefault(emptyList())

        suspend fun rememberCapabilityEvidence(
            draft: ConfigDraft,
            telemetry: ServiceTelemetrySnapshot,
        ) {
            val fingerprint = runCatching { networkFingerprintProvider.capture() }.getOrNull() ?: return
            buildRelayCapabilityObservation(draft, telemetry)?.let { (authority, observation) ->
                serverCapabilityStore.rememberRelayObservation(
                    fingerprint = fingerprint,
                    authority = authority,
                    relayProfileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId },
                    observation = observation,
                    source = "config_viewmodel",
                )
            }
            buildDirectPathCapabilityObservation(telemetry)?.let { (authority, observation) ->
                serverCapabilityStore.rememberDirectPathObservation(
                    fingerprint = fingerprint,
                    authority = authority,
                    observation = observation,
                    source = "config_viewmodel",
                )
            }
        }
    }
