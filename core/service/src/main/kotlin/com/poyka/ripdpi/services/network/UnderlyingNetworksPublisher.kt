package com.poyka.ripdpi.services.network

/**
 * Publishes an underlying network to the VPN builder when the network is available and safe.
 *
 * "Safe" means the network is validated and not behind a captive portal.
 * Publishing through a captive portal would route VPN traffic through a portal intercept.
 */
class UnderlyingNetworksPublisher {
    /**
     * Calls [recorder].setNetworks with [network] when safe conditions are met.
     *
     * @return true if the network was published; false if skipped.
     */
    fun <T> publishIfSafe(
        recorder: UnderlyingNetworksRecorder<T>,
        network: T?,
        validated: Boolean,
        captivePortal: Boolean,
    ): Boolean {
        if (network == null || !validated || captivePortal) return false
        recorder.setNetworks(network)
        return true
    }

    /** Clears the underlying network binding (e.g. on network lost). */
    fun <T> clearNetworks(recorder: UnderlyingNetworksRecorder<T>) {
        recorder.clear()
    }
}

/** Abstraction over the VPN builder / recorder so tests don't need Android runtime. */
interface UnderlyingNetworksRecorder<T> {
    fun setNetworks(network: T?)

    fun clear()
}
