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
    val pendingPkcs12Uri: Uri? = null,
)

internal class ConfigMasqueImportController(
    private val importCertificateChain: (Uri) -> Unit,
    private val importPrivateKey: (Uri) -> Unit,
    private val importPkcs12: (Uri, String?) -> Unit,
) {
    private val mutableState = MutableStateFlow(ConfigMasqueImportState())
    val state = mutableState.asStateFlow()

    fun begin(action: MasqueImportAction) {
        mutableState.update { it.copy(pendingAction = action) }
    }

    fun onDocumentPicked(uri: Uri?) {
        val action = mutableState.value.pendingAction
        mutableState.update {
            it.copy(
                pendingAction = null,
                pendingPkcs12Uri = uri.takeIf { action == MasqueImportAction.Pkcs12 },
            )
        }
        if (uri == null) return
        when (action) {
            MasqueImportAction.CertificateChain -> importCertificateChain(uri)
            MasqueImportAction.PrivateKey -> importPrivateKey(uri)
            MasqueImportAction.Pkcs12, null -> Unit
        }
    }

    fun dismissPkcs12() {
        mutableState.update { it.copy(pendingPkcs12Uri = null) }
    }

    fun importPendingPkcs12(password: String?) {
        val uri = mutableState.value.pendingPkcs12Uri ?: return
        dismissPkcs12()
        importPkcs12(uri, password)
    }

    fun clear() {
        mutableState.value = ConfigMasqueImportState()
    }
}
