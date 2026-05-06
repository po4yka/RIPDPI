package com.poyka.ripdpi.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger

internal class VpnUnderlyingNetworkBinder(
    private val service: RipDpiVpnService,
) {
    @android.annotation.SuppressLint("MissingPermission")
    fun syncFromActiveNetwork() {
        val connectivityManager = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hasNetworkStatePermission = hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)
        val activeNetwork =
            if (hasNetworkStatePermission) {
                connectivityManager.activeNetwork
            } else {
                null
            }
        val bindingAudit =
            resolveVpnUpstreamNetworkBindingAudit(
                hasNetworkStatePermission = hasNetworkStatePermission,
                activeNetworkAvailable = activeNetwork != null,
            )
        Logger.i { "vpn upstream binding audit: ${bindingAudit.logSummary()}" }
        service.setUnderlyingNetworks(
            if (bindingAudit.bindsActiveNetwork && activeNetwork != null) {
                arrayOf(activeNetwork)
            } else {
                null
            },
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(service, permission) == PackageManager.PERMISSION_GRANTED
}
