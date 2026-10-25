package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton
import com.poyka.ripdpi.data.NetworkHandoverEvent as SharedNetworkHandoverEvent
import com.poyka.ripdpi.data.NetworkHandoverMonitor as SharedNetworkHandoverMonitor

typealias NetworkHandoverEvent = SharedNetworkHandoverEvent
typealias NetworkHandoverMonitor = SharedNetworkHandoverMonitor

/**
 * Watches the underlying network via `ConnectivityManager` callbacks and emits
 * a `NetworkHandoverEvent` when a qualifying Wi-Fi ↔ cellular switch is
 * detected. `NetworkHandoverProcessor` consumes the event and drives a
 * retry-backed runtime restart. See this module's `README.md`,
 * "Network handover".
 */
@Singleton
class DefaultNetworkHandoverMonitor
    @Inject
    constructor(
        private val physicalNetworkObserver: PhysicalNetworkObserver,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        @param:ApplicationScope private val scope: kotlinx.coroutines.CoroutineScope,
    ) : NetworkHandoverMonitor {
        private companion object {
            private const val DebounceWindowMs = 2_000L
            private const val StopTimeoutMs = 5_000L
        }

        override val events: SharedFlow<NetworkHandoverEvent> =
            observeNetworkHandoverEvents(
                signals = physicalNetworkObserver.changes.map { Unit },
                captureFingerprint = networkFingerprintProvider::capture,
                debounceMs = DebounceWindowMs,
                clock = System::currentTimeMillis,
            ).shareIn(
                scope = scope,
                started =
                    kotlinx.coroutines.flow.SharingStarted
                        .WhileSubscribed(stopTimeoutMillis = StopTimeoutMs),
                replay = 0,
            )
    }

internal fun observeNetworkHandoverEvents(
    signals: Flow<Unit>,
    captureFingerprint: () -> NetworkFingerprint?,
    debounceMs: Long,
    clock: () -> Long,
): Flow<NetworkHandoverEvent> =
    flow {
        var previousFingerprint = captureFingerprint()
        Logger.withTag("NetworkHandover").i {
            "initialCapturePresent=${previousFingerprint != null} " +
                "initialLeaseTokenPresent=${previousFingerprint?.directDnsUnderlayGeneration != null}"
        }
        val eventSignals =
            if (debounceMs > 0L) {
                debouncedSignals(signals, debounceMs)
            } else {
                signals
            }
        eventSignals.collect {
            val currentFingerprint = captureFingerprint()
            val classification = classifyNetworkHandover(previousFingerprint, currentFingerprint)
            Logger.withTag("NetworkHandover").i {
                "classification=${classification ?: "none"} " +
                    "changedFields=${networkHandoverChangedFields(previousFingerprint, currentFingerprint)} " +
                    "leaseTokenBeforePresent=${previousFingerprint?.directDnsUnderlayGeneration != null} " +
                    "leaseTokenAfterPresent=${currentFingerprint?.directDnsUnderlayGeneration != null}"
            }
            if (classification != null) {
                emit(
                    NetworkHandoverEvent(
                        previousFingerprint = previousFingerprint,
                        currentFingerprint = currentFingerprint,
                        classification = classification,
                        occurredAt = clock(),
                    ),
                )
            }
            previousFingerprint = currentFingerprint
        }
    }

private fun networkHandoverChangedFields(
    previous: NetworkFingerprint?,
    current: NetworkFingerprint?,
): String =
    listOf(
        "presence" to ((previous != null) != (current != null)),
        "transport" to (previous?.transport != current?.transport),
        "validation" to (previous?.networkValidated != current?.networkValidated),
        "captive" to (previous?.captivePortalDetected != current?.captivePortalDetected),
        "privateDns" to (previous?.privateDnsMode != current?.privateDnsMode),
        "dns" to (previous?.dnsServers != current?.dnsServers),
        "wifi" to (previous?.wifi != current?.wifi),
        "cellular" to (previous?.cellular != current?.cellular),
        "metered" to (previous?.metered != current?.metered),
        "leaseToken" to (previous?.directDnsUnderlayGeneration != current?.directDnsUnderlayGeneration),
        "physical" to (previous?.physicalNetworkSignature != current?.physicalNetworkSignature),
    ).filter { it.second }.joinToString(",") { it.first }.ifEmpty { "none" }

@OptIn(FlowPreview::class)
private fun debouncedSignals(
    signals: Flow<Unit>,
    debounceMs: Long,
): Flow<Unit> = signals.debounce(debounceMs)

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkHandoverMonitorModule {
    @Binds
    @Singleton
    abstract fun bindNetworkHandoverMonitor(monitor: DefaultNetworkHandoverMonitor): NetworkHandoverMonitor
}
