package com.poyka.ripdpi.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider
import com.poyka.ripdpi.data.VpnRouteFamilyIpv4
import com.poyka.ripdpi.data.VpnRouteFamilyIpv6
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.Inet4Address
import java.net.Inet6Address
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class VpnRouteObservationAuthority
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val receiptStore: VpnRouteLifecycleReceiptStore,
    ) {
        private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        private val ownedNetworks = mutableSetOf<Network>()
        private val rejectedNetworks = mutableSetOf<Network>()
        private var registeredCallback: ConnectivityManager.NetworkCallback? = null
        private var registrationEpoch = 0L

        @android.annotation.SuppressLint("MissingPermission")
        @Synchronized
        fun start() {
            if (registeredCallback != null) return
            registrationEpoch += 1L
            val callback = ownedVpnCallback(registrationEpoch)
            registeredCallback = callback
            try {
                connectivityManager.registerNetworkCallback(ownedVpnNetworkRequest(Build.VERSION.SDK_INT), callback)
                receiptStore.setObserverAvailable(true)
            } catch (_: RuntimeException) {
                registeredCallback = null
                registrationEpoch += 1L
                receiptStore.setObserverAvailable(false)
            }
        }

        @Synchronized
        fun stop() {
            val callback = registeredCallback ?: return
            registeredCallback = null
            registrationEpoch += 1L
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            ownedNetworks.clear()
            rejectedNetworks.clear()
            receiptStore.setObserverAvailable(false)
        }

        internal fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            val ownerVerification = ownerVerification(capabilities)
            if (ownerVerification == null) {
                synchronized(this) {
                    ownedNetworks.remove(network)
                    rejectedNetworks += network
                }
                receiptStore.discardObservation(network)
                return
            }
            synchronized(this) {
                rejectedNetworks.remove(network)
                ownedNetworks += network
            }
            receiptStore.observeCapabilities(
                networkKey = network,
                hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                ownerVerification = ownerVerification,
            )
        }

        internal fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties,
        ) {
            if (synchronized(this) { network in rejectedNetworks }) {
                receiptStore.discardObservation(network)
                return
            }
            receiptStore.observeDefaultRoutes(network, linkProperties.defaultRouteFamilies())
        }

        internal fun onLost(network: Network) {
            val wasOwned =
                synchronized(this) {
                    rejectedNetworks.remove(network)
                    ownedNetworks.remove(network)
                }
            if (wasOwned) {
                receiptStore.observeLost(network)
            } else {
                receiptStore.discardObservation(network)
            }
        }

        private fun ownedVpnCallback(epoch: Long): ConnectivityManager.NetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    ifCurrentRegistration(epoch) {
                        receiptStore.observeAvailable(network)
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    ifCurrentRegistration(epoch) {
                        this@VpnRouteObservationAuthority.onCapabilitiesChanged(network, capabilities)
                    }
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    ifCurrentRegistration(epoch) {
                        this@VpnRouteObservationAuthority.onLinkPropertiesChanged(network, linkProperties)
                    }
                }

                override fun onLost(network: Network) {
                    ifCurrentRegistration(epoch) {
                        this@VpnRouteObservationAuthority.onLost(network)
                    }
                }
            }

        private inline fun ifCurrentRegistration(
            epoch: Long,
            action: () -> Unit,
        ) {
            synchronized(this) {
                if (registeredCallback != null && registrationEpoch == epoch) action()
            }
        }

        private fun ownerVerification(capabilities: NetworkCapabilities): VpnRouteOwnerVerification? {
            val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val isVpnOnly = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            return when {
                !isVpn || !isVpnOnly -> {
                    null
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && capabilities.ownerUid == Process.myUid() -> {
                    VpnRouteOwnerVerification.Verified
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    null
                }

                receiptStore.hasCorrelatableReceipt() -> {
                    VpnRouteOwnerVerification.Unavailable
                }

                else -> {
                    null
                }
            }
        }
    }

internal fun ownedVpnNetworkRequest(apiLevel: Int): NetworkRequest {
    val builder = NetworkRequest.Builder()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && apiLevel >= Build.VERSION_CODES.R) {
        builder.clearCapabilities()
    } else {
        builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
    }
    builder.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && apiLevel >= Build.VERSION_CODES.S) {
        builder.setIncludeOtherUidNetworks(true)
    }
    return builder.build()
}

private fun LinkProperties.defaultRouteFamilies(): Set<String> =
    routes
        .asSequence()
        .filter { it.isDefaultRoute }
        .mapNotNull { route ->
            when (route.destination.address) {
                is Inet4Address -> VpnRouteFamilyIpv4
                is Inet6Address -> VpnRouteFamilyIpv6
                else -> null
            }
        }.toSet()

@Module
@InstallIn(SingletonComponent::class)
internal abstract class VpnRouteEvidenceModule {
    @Binds
    @Singleton
    abstract fun bindVpnRouteEvidenceProvider(store: VpnRouteLifecycleReceiptStore): VpnRouteEvidenceProvider
}
