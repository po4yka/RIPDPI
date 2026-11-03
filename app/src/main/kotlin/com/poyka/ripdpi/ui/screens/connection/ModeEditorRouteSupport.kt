package com.poyka.ripdpi.ui.screens.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayVlessTransportXhttp

internal fun updateRelayKind(
    viewModel: ConnectionViewModel,
    relayKind: String,
) {
    viewModel.updateDraft {
        when (relayKind) {
            RelayKindCloudflareTunnel,
            RelayKindVless,
            -> {
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
    viewModel: ConnectionViewModel,
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

internal fun createModeEditorExternalActions(
    viewModel: ConnectionViewModel,
    context: Context,
    requestCoarseLocationPermission: (String) -> Unit,
    requestDocument: (MasqueImportRequest) -> Unit,
): ModeEditorExternalActions =
    ModeEditorExternalActions(
        onRelayMasqueCloudflareGeohashEnabledChanged = { enabled ->
            updateMasqueGeohash(viewModel, context, requestCoarseLocationPermission, enabled)
        },
        onRelayMasqueImportCertificateChainClicked = {
            viewModel.currentEditorSessionId?.let { sessionId ->
                requestDocument(MasqueImportRequest(MasqueImportAction.CertificateChain, sessionId))
            }
        },
        onRelayMasqueImportPrivateKeyClicked = {
            viewModel.currentEditorSessionId?.let { sessionId ->
                requestDocument(MasqueImportRequest(MasqueImportAction.PrivateKey, sessionId))
            }
        },
        onRelayMasqueImportPkcs12Clicked = {
            viewModel.currentEditorSessionId?.let { sessionId ->
                requestDocument(MasqueImportRequest(MasqueImportAction.Pkcs12, sessionId))
            }
        },
    )

internal fun handleModeEditorExitDecision(
    decision: ConnectionEditorExitDecision,
    onBack: () -> Unit,
    onConfirmDiscard: () -> Unit,
) {
    when (decision) {
        ConnectionEditorExitDecision.Blocked -> Unit
        ConnectionEditorExitDecision.ConfirmDiscard -> onConfirmDiscard()
        ConnectionEditorExitDecision.Exit -> onBack()
    }
}
