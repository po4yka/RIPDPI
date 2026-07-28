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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
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
        @ApplicationScope scope: CoroutineScope,
    ) : NetworkPathValidationSource {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = publishCurrentEvidence()

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) = publishCurrentEvidence()

                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: LinkProperties,
                        ) = publishCurrentEvidence()

                        override fun onLost(network: Network) = publishCurrentEvidence()

                        private fun publishCurrentEvidence() {
                            trySend(captureEvidence())
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
                trySend(captureEvidence())
                awaitClose {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }.distinctUntilChanged()

        private fun captureEvidence(): NetworkPathValidationEvidence =
            captureCurrentPathValidationEvidence(
                connectivityManager = connectivityManager,
                permissionAvailable = hasNetworkStatePermission(),
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
