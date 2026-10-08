package com.poyka.ripdpi.services

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.poyka.ripdpi.data.DiagnosticsNetworkEpochProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** One process-owned passive physical observer. Starting/stopping our VPN never replaces its token. */
@Singleton
class CandidateRelayNetworkEpoch
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : DiagnosticsNetworkEpochProvider {
        private val state =
            CandidatePhysicalNetworkObserver<Network>(Build.VERSION.SDK_INT >= BlockedCallbackMinimumApi)
        private val registration = state.beginRegistration()
        private val registered = register(context)
        val changes = state.changes

        override fun capture(): CandidatePhysicalNetworkToken? = if (registered) state.capture() else null

        // Manifest ACCESS_NETWORK_STATE; failed registration is unavailable, never Ready.
        @SuppressLint("MissingPermission")
        private fun register(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) =
                        state.available(
                            registration,
                            network,
                            Build.VERSION.SDK_INT >= BestMatchingCallbackMinimumApi,
                        )

                    override fun onLost(network: Network) = state.lost(registration, network)

                    override fun onBlockedStatusChanged(
                        network: Network,
                        blocked: Boolean,
                    ) = state.blocked(registration, network, blocked)

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        val suspended =
                            Build.VERSION.SDK_INT >= SuspensionCapabilityMinimumApi &&
                                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                        val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        val physical =
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        val fingerprint =
                            listOf(
                                internet,
                                physical,
                                suspended,
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                                (0..LastFingerprintTransportIndex).filter(capabilities::hasTransport),
                            ).joinToString("|").privateDigest()
                        state.capabilities(registration, network, fingerprint, internet && physical && !suspended)
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        properties: LinkProperties,
                    ) {
                        val fingerprint =
                            listOf(
                                properties.interfaceName.orEmpty(),
                                properties.linkAddresses
                                    .map { it.toString() }
                                    .sorted()
                                    .joinToString(),
                                properties.routes
                                    .map { it.toString() }
                                    .sorted()
                                    .joinToString(),
                                properties.dnsServers
                                    .map { it.hostAddress.orEmpty() }
                                    .sorted()
                                    .joinToString(),
                                if (Build.VERSION.SDK_INT >= AndroidMtuApi) {
                                    properties.mtu.toString()
                                } else {
                                    "mtu-unavailable"
                                },
                            ).joinToString("|").privateDigest()
                        state.links(
                            registration,
                            network,
                            fingerprint,
                            properties.linkAddresses.isNotEmpty() && properties.routes.isNotEmpty(),
                        )
                    }
                }
            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            return try {
                if (Build.VERSION.SDK_INT >= BestMatchingCallbackMinimumApi) {
                    manager.registerBestMatchingNetworkCallback(
                        request,
                        callback,
                        android.os.Handler(android.os.Looper.getMainLooper()),
                    )
                } else {
                    manager.registerNetworkCallback(request, callback)
                }
                true
            } catch (_: RuntimeException) {
                state.endRegistration(registration)
                false
            }
        }
    }

private fun String.privateDigest() =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") {
        "%02x".format(it)
    }

private const val BlockedCallbackMinimumApi = 29
private const val SuspensionCapabilityMinimumApi = 28
private const val BestMatchingCallbackMinimumApi = 31
private const val LastFingerprintTransportIndex = 6

private const val AndroidMtuApi = 29
