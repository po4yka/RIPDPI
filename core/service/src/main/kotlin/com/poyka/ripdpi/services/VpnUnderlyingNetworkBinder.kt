package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger

internal class VpnUnderlyingNetworkBinder(
    private val service: RipDpiVpnService,
) {
    @Volatile
    private var activeUnderlyingNetwork: Network? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun captureActiveNetwork() {
        activeUnderlyingNetwork = currentActiveNetwork()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun syncFromActiveNetwork() {
        val hasNetworkStatePermission = hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)
        val activeNetwork = currentActiveNetwork()
        val bindingAudit =
            resolveVpnUpstreamNetworkBindingAudit(
                hasNetworkStatePermission = hasNetworkStatePermission,
                activeNetworkAvailable = activeNetwork != null,
            )
        activeUnderlyingNetwork = activeNetwork
        Logger.i { "vpn upstream binding audit: ${bindingAudit.logSummary()}" }
        service.setUnderlyingNetworks(
            if (bindingAudit.bindsActiveNetwork && activeNetwork != null) {
                arrayOf(activeNetwork)
            } else {
                null
            },
        )
    }

    fun resolveHost(host: String): Array<String> {
        val network = activeUnderlyingNetwork ?: throw java.net.UnknownHostException("no underlying network")
        return network.getAllByName(host).mapNotNull { it.hostAddress }.toTypedArray()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun currentActiveNetwork(): Network? {
        if (!hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) return null
        val connectivityManager = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivityManager.activeNetwork
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(service, permission) == PackageManager.PERMISSION_GRANTED
}
