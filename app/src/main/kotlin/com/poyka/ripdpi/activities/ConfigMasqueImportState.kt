package com.poyka.ripdpi.activities

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class MasqueImportAction {
    CertificateChain,
    PrivateKey,
    Pkcs12,
}

internal data class ConfigMasqueImportState(
    val pendingAction: MasqueImportAction? = null,
    val pendingSessionId: Long? = null,
    val pendingPkcs12Uri: Uri? = null,
    val pendingPkcs12SessionId: Long? = null,
)

internal class ConfigMasqueImportController(
    private val importCertificateChain: (Uri, Long) -> Unit,
    private val importPrivateKey: (Uri, Long) -> Unit,
    private val importPkcs12: (Uri, String?, Long) -> Unit,
) {
    private val mutableState = MutableStateFlow(ConfigMasqueImportState())
    val state = mutableState.asStateFlow()

    fun begin(
        action: MasqueImportAction,
        sessionId: Long,
    ) {
        mutableState.update { it.copy(pendingAction = action, pendingSessionId = sessionId) }
    }

    fun onDocumentPicked(uri: Uri?) {
        val pending = mutableState.value
        val action = pending.pendingAction
        val sessionId = pending.pendingSessionId
        mutableState.update {
            it.copy(
                pendingAction = null,
                pendingSessionId = null,
                pendingPkcs12Uri = uri.takeIf { action == MasqueImportAction.Pkcs12 },
                pendingPkcs12SessionId = sessionId.takeIf { action == MasqueImportAction.Pkcs12 },
            )
        }
        if (uri == null || sessionId == null) return
        when (action) {
            MasqueImportAction.CertificateChain -> importCertificateChain(uri, sessionId)
            MasqueImportAction.PrivateKey -> importPrivateKey(uri, sessionId)
            MasqueImportAction.Pkcs12, null -> Unit
        }
    }

    fun dismissPkcs12() {
        mutableState.update { it.copy(pendingPkcs12Uri = null, pendingPkcs12SessionId = null) }
    }

    fun importPendingPkcs12(password: String?) {
        val pending = mutableState.value
        val uri = pending.pendingPkcs12Uri ?: return
        val sessionId = pending.pendingPkcs12SessionId ?: return
        dismissPkcs12()
        importPkcs12(uri, password, sessionId)
    }

    fun clear() {
        mutableState.value = ConfigMasqueImportState()
    }
}
