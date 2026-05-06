package com.poyka.ripdpi.ui.screens.config

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayVlessTransportXhttp

internal fun updateRelayKind(
    viewModel: ConfigViewModel,
    relayKind: String,
) {
    viewModel.updateDraft {
        when (relayKind) {
            RelayKindCloudflareTunnel -> {
                copy(
                    relayKind = relayKind,
                    relayVlessTransport = RelayVlessTransportXhttp,
                    relayUdpEnabled = false,
                )
            }

            RelayKindShadowTlsV3,
            RelayKindNaiveProxy,
            -> {
                copy(
                    relayKind = relayKind,
                    relayUdpEnabled = false,
                )
            }

            else -> {
                copy(relayKind = relayKind)
            }
        }
    }
}

internal fun updateMasqueGeohash(
    viewModel: ConfigViewModel,
    context: Context,
    requestCoarseLocationPermission: (String) -> Unit,
    enabled: Boolean,
) {
    if (!enabled) {
        viewModel.updateDraft { copy(relayMasqueCloudflareGeohashEnabled = false) }
        return
    }

    val permissionState =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (permissionState == PackageManager.PERMISSION_GRANTED) {
        viewModel.updateDraft { copy(relayMasqueCloudflareGeohashEnabled = true) }
    } else {
        requestCoarseLocationPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
