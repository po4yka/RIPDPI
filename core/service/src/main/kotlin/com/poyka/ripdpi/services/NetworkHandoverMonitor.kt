package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkHandoverEvent(
    val previousFingerprint: NetworkFingerprint?,
    val currentFingerprint: NetworkFingerprint?,
    val classification: String,
    val occurredAt: Long,
) {
    val isActionable: Boolean
        get() = currentFingerprint != null && classification != "connectivity_loss"
}

interface NetworkHandoverMonitor {
    val events: SharedFlow<NetworkHandoverEvent>
}

@Singleton
class DefaultNetworkHandoverMonitor
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        @param:ApplicationScope private val scope: kotlinx.coroutines.CoroutineScope,
    ) : NetworkHandoverMonitor {
        private companion object {
            private const val DebounceWindowMs = 2_000L
            private const val StopTimeoutMs = 5_000L
        }

        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        override val events: SharedFlow<NetworkHandoverEvent> =
            observeNetworkHandoverEvents(
                signals = networkSignals(),
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

        private fun networkSignals(): Flow<Unit> =
            callbackFlow {
                val callback =
                    object : NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            trySend(Unit)
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            trySend(Unit)
                        }

                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: LinkProperties,
                        ) {
                            trySend(Unit)
                        }

                        override fun onLost(network: Network) {
                            trySend(Unit)
                        }
                    }

                runCatching {
                    if (hasNetworkStatePermission()) {
                        connectivityManager.registerDefaultNetworkCallback(callback)
                    } else {
                        error("android.permission.ACCESS_NETWORK_STATE is required to observe network handover events")
                    }
                }.onFailure {
                    close(it)
                }

                awaitClose {
                    runCatching {
                        connectivityManager.unregisterNetworkCallback(callback)
                    }
                }
            }

        private fun hasNetworkStatePermission(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) ==
                PackageManager.PERMISSION_GRANTED
    }

internal fun observeNetworkHandoverEvents(
    signals: Flow<Unit>,
    captureFingerprint: () -> NetworkFingerprint?,
    debounceMs: Long,
    clock: () -> Long,
): Flow<NetworkHandoverEvent> =
    flow {
        var previousFingerprint = captureFingerprint()
        val eventSignals =
            if (debounceMs > 0L) {
                debouncedSignals(signals, debounceMs)
            } else {
                signals
            }
        eventSignals.collect {
            val currentFingerprint = captureFingerprint()
            val classification = classifyNetworkHandover(previousFingerprint, currentFingerprint)
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
