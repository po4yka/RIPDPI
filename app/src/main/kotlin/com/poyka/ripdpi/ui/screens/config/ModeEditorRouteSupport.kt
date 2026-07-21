package com.poyka.ripdpi.ui.screens.config

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.activities.ConfigEditorExitDecision
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

internal fun handleMasqueDocumentResult(
    viewModel: ConfigViewModel,
    request: MasqueImportRequest?,
    uri: Uri?,
    onPkcs12Selected: (PendingMasquePkcs12Import) -> Unit,
) {
    if (uri == null || request == null) return
    when (request.action) {
        MasqueImportAction.CertificateChain -> {
            viewModel.importRelayMasqueCertificateChain(uri, request.sessionId)
        }

        MasqueImportAction.PrivateKey -> {
            viewModel.importRelayMasquePrivateKey(uri, request.sessionId)
        }

        MasqueImportAction.Pkcs12 -> {
            onPkcs12Selected(PendingMasquePkcs12Import(uri, request.sessionId))
        }
    }
}

internal fun createModeEditorExternalActions(
    viewModel: ConfigViewModel,
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
    decision: ConfigEditorExitDecision,
    onBack: () -> Unit,
    onConfirmDiscard: () -> Unit,
) {
    when (decision) {
        ConfigEditorExitDecision.Blocked -> Unit
        ConfigEditorExitDecision.ConfirmDiscard -> onConfirmDiscard()
        ConfigEditorExitDecision.Exit -> onBack()
    }
}
