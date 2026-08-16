package com.poyka.ripdpi.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import com.poyka.ripdpi.data.NetworkPathObservation
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Current coarse VPN and non-VPN path state. Contains no network identifiers or addresses. */
interface NetworkPathValidationSource {
    val evidence: StateFlow<NetworkPathValidationEvidence>
}

@Singleton
internal class AndroidNetworkPathValidationSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val underlayObservationProvider: AuthoritativeVpnUnderlayObservationProvider,
        private val vpnRouteEvidenceProvider: VpnRouteEvidenceProvider,
        private val sessionCoordinator: RuntimeSessionCoordinator,
    ) : NetworkPathValidationSource {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val transitionTimeline =
            NetworkTransitionTimeline(
                scope = scope,
                withSessionAdmission = sessionCoordinator::withNetworkTransitionAdmission,
                persist = sessionCoordinator::handleNetworkTransition,
            )

        init {
            sessionCoordinator.registerNetworkTransitionFlush(transitionTimeline::flush)
        }

        override val evidence: StateFlow<NetworkPathValidationEvidence> =
            observeEvidence().stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = captureEvidence(),
            )

        @SuppressLint("MissingPermission")
        private fun observeEvidence() =
            callbackFlow {
                if (!hasNetworkStatePermission()) {
                    trySend(NetworkPathValidationEvidence(captureStatus = "permission_unavailable"))
                    close()
                    return@callbackFlow
                }

                val publishCurrentEvidence = { trySend(captureEvidence()) }
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            transitionTimeline.recordAvailable(network)
                            publishCurrentEvidence()
                        }

                        override fun onLosing(
                            network: Network,
                            maxMsToLive: Int,
                        ) {
                            transitionTimeline.recordLosing(network, maxMsToLive)
                            publishCurrentEvidence()
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            transitionTimeline.recordCapabilities(network, networkCapabilities)
                            publishCurrentEvidence()
                        }

                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: LinkProperties,
                        ) {
                            transitionTimeline.recordLinkProperties(network)
                            publishCurrentEvidence()
                        }

                        override fun onLost(network: Network) {
                            transitionTimeline.recordLost(network)
                            publishCurrentEvidence()
                        }
                    }
                val request =
                    NetworkRequest
                        .Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build()
                val registered =
                    runCatching {
                        connectivityManager.registerNetworkCallback(request, callback)
                    }.isSuccess
                if (!registered) {
                    trySend(NetworkPathValidationEvidence(captureStatus = "permission_unavailable"))
                    close()
                    return@callbackFlow
                }
                val authorityObserver =
                    launch {
                        underlayObservationProvider.changes.collect {
                            publishCurrentEvidence()
                        }
                    }
                val vpnRouteObserver =
                    launch {
                        vpnRouteEvidenceProvider.changes.collectLatest {
                            publishCurrentEvidence()
                            vpnRouteEvidenceProvider.convergenceRefreshDelayMillis()?.let { delayMillis ->
                                delay(delayMillis)
                                publishCurrentEvidence()
                            }
                        }
                    }
                publishCurrentEvidence()
                awaitClose {
                    authorityObserver.cancel()
                    vpnRouteObserver.cancel()
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }.distinctUntilChanged()

        private fun captureEvidence(): NetworkPathValidationEvidence =
            captureCurrentPathValidationEvidence(
                connectivityManager = connectivityManager,
                permissionAvailable = hasNetworkStatePermission(),
                underlay =
                    sanitizeAuthoritativeUnderlayObservation(
                        runCatching(underlayObservationProvider::capture).getOrDefault(NetworkPathObservation()),
                    ),
                vpnRouteEvidence =
                    runCatching(vpnRouteEvidenceProvider::capture)
                        .getOrDefault(VpnRouteEvidence())
                        .toDiagnosticsSnapshot(),
            )

        private fun hasNetworkStatePermission(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) ==
                PackageManager.PERMISSION_GRANTED
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkPathValidationSourceModule {
    @Binds
    @Singleton
    abstract fun bindNetworkPathValidationSource(
        source: AndroidNetworkPathValidationSource,
    ): NetworkPathValidationSource
}
