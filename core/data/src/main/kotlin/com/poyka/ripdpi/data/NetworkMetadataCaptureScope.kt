package com.poyka.ripdpi.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Brackets platform metadata reads with process-local evidence of physical and default-route continuity. */
@Singleton
class NetworkMetadataCaptureScope
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
        private val epochProvider: DiagnosticsNetworkEpochProvider,
        private val underlayProvider: AuthoritativeVpnUnderlayObservationProvider,
        private val vpnRouteProvider: VpnRouteEvidenceProvider,
    ) {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)

        // A callback revision detects a route change even if the same default route returns.
        private val defaultRouteRevision = AtomicLong()
        private val registered = registerDefaultRouteObserver()

        @SuppressLint("MissingPermission")
        fun begin(): Capture? {
            if (!registered) return null
            val revision = revision()
            val network = connectivity.activeNetwork
            // If only one source sees a path, the evidence is incomplete.
            return if ((network == null) != (revision.epoch == null)) {
                null
            } else {
                val capabilities = network?.let(connectivity::getNetworkCapabilities)
                val links = network?.let(connectivity::getLinkProperties)
                Capture(network, capabilities, links) {
                    links == network?.let(connectivity::getLinkProperties) &&
                        network == connectivity.activeNetwork &&
                        revision == revision()
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun registerDefaultRouteObserver(): Boolean =
            runCatching {
                connectivity.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            defaultRouteRevision.incrementAndGet()
                        }

                        override fun onLost(network: Network) {
                            defaultRouteRevision.incrementAndGet()
                        }

                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: LinkProperties,
                        ) {
                            defaultRouteRevision.incrementAndGet()
                        }

                        override fun onBlockedStatusChanged(
                            network: Network,
                            blocked: Boolean,
                        ) {
                            defaultRouteRevision.incrementAndGet()
                        }
                    },
                )
            }.isSuccess

        private fun revision(): RouteRevision =
            RouteRevision(
                epoch = epochProvider.capture(),
                defaultRoute = defaultRouteRevision.get(),
                underlay = underlayProvider.changes.value,
                vpnRoute = vpnRouteProvider.changes.value,
            )

        class Capture internal constructor(
            val network: Network?,
            val capabilities: NetworkCapabilities?,
            val links: LinkProperties?,
            private val checkCurrent: () -> Boolean,
        ) {
            fun isCurrent(): Boolean = checkCurrent()
        }

        private data class RouteRevision(
            val epoch: DiagnosticsNetworkEpoch?,
            val defaultRoute: Long,
            val underlay: Long,
            val vpnRoute: Long,
        )
    }
