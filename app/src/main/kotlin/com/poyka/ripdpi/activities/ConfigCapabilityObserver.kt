package com.poyka.ripdpi.activities

import com.poyka.ripdpi.config.relay.buildDirectPathCapabilityObservation
import com.poyka.ripdpi.config.relay.buildRelayCapabilityObservation
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigCapabilityObserver
    @Inject
    constructor(
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val serverCapabilityStore: ServerCapabilityStore,
        private val dispatchers: AppCoroutineDispatchers,
    ) {
        suspend fun relayCapabilitiesForCurrentNetwork(): List<ServerCapabilityRecord> =
            withContext(dispatchers.io) {
                runCatching {
                    networkFingerprintProvider
                        .capture()
                        ?.let { fingerprint ->
                            serverCapabilityStore.relayCapabilitiesForFingerprint(fingerprint.scopeKey())
                        }.orEmpty()
                }.getOrDefault(emptyList())
            }

        suspend fun rememberCapabilityEvidence(
            draft: ConfigDraft,
            telemetry: ServiceTelemetrySnapshot,
        ) = withContext(dispatchers.io) {
            val fingerprint = runCatching { networkFingerprintProvider.capture() }.getOrNull() ?: return@withContext
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
