package com.poyka.ripdpi.ui.screens.connection

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

internal fun createModeEditorExternalActions(
    viewModel: ConnectionViewModel,
    requestDocument: (MasqueImportRequest) -> Unit,
): ModeEditorExternalActions =
    ModeEditorExternalActions(
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
