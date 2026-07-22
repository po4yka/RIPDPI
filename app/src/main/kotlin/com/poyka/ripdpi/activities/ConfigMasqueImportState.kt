package com.poyka.ripdpi.activities

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MasqueImportActionSavedStateKey = "config_masque_import_action"
private const val MasqueImportSessionSavedStateKey = "config_masque_import_session"
private const val MasqueImportOwnerSavedStateKey = "config_masque_import_owner"
private const val MasquePickedDocumentUriSavedStateKey = "config_masque_picked_document_uri"
private const val MasquePkcs12UriSavedStateKey = "config_masque_pkcs12_uri"
private const val MasquePkcs12SessionSavedStateKey = "config_masque_pkcs12_session"

internal enum class MasqueImportAction {
    CertificateChain,
    PrivateKey,
    Pkcs12,
}

internal data class ConfigMasqueImportState(
    val pendingAction: MasqueImportAction? = null,
    val pendingSessionId: Long? = null,
    val recoveryOwnerId: String? = null,
    val pickedDocumentUri: Uri? = null,
    val pendingPkcs12Uri: Uri? = null,
    val pendingPkcs12SessionId: Long? = null,
    val sessionReady: Boolean = false,
)

internal class ConfigMasqueImportController(
    private val importCertificateChain: (Uri, Long) -> Boolean,
    private val importPrivateKey: (Uri, Long) -> Boolean,
    private val importPkcs12: (Uri, String?, Long) -> Boolean,
    initialState: ConfigMasqueImportState = ConfigMasqueImportState(),
    private val onStateChanged: (ConfigMasqueImportState) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()

    fun begin(
        action: MasqueImportAction,
        sessionId: Long,
        recoveryOwnerId: String,
    ) {
        updateState {
            it.copy(
                pendingAction = action,
                pendingSessionId = sessionId,
                recoveryOwnerId = recoveryOwnerId,
                pickedDocumentUri = null,
                sessionReady = true,
            )
        }
    }

    fun onDocumentPicked(uri: Uri?) {
        val pending = mutableState.value
        val action = pending.pendingAction
        val sessionId = pending.pendingSessionId
        when {
            uri == null || sessionId == null || action == null -> {
                clearPendingDocument()
            }

            action == MasqueImportAction.Pkcs12 -> {
                updateState {
                    it.copy(
                        pendingAction = null,
                        pendingSessionId = null,
                        pickedDocumentUri = null,
                        pendingPkcs12Uri = uri,
                        pendingPkcs12SessionId = sessionId,
                    )
                }
            }

            pending.sessionReady -> {
                if (importDocument(action, uri, sessionId)) {
                    clearPendingDocument()
                } else {
                    updateState { it.copy(pickedDocumentUri = uri, sessionReady = false) }
                }
            }

            else -> {
                updateState { it.copy(pickedDocumentUri = uri) }
            }
        }
    }

    fun dismissPkcs12() {
        updateState { current ->
            current.copy(
                pendingPkcs12Uri = null,
                pendingPkcs12SessionId = null,
                recoveryOwnerId = current.recoveryOwnerId.takeIf { current.pendingAction != null },
            )
        }
    }

    fun importPendingPkcs12(password: String?) {
        val pending = mutableState.value
        val uri = pending.pendingPkcs12Uri
        val sessionId = pending.pendingPkcs12SessionId
        if (pending.sessionReady && uri != null && sessionId != null) {
            if (importPkcs12(uri, password, sessionId)) {
                dismissPkcs12()
            } else {
                updateState { it.copy(sessionReady = false) }
            }
        }
    }

    fun rebindPendingSession(
        sessionId: Long,
        recoveryOwnerId: String,
    ) {
        val current = mutableState.value
        if (current.hasPendingImport && current.recoveryOwnerId != recoveryOwnerId) {
            clear()
            return
        }
        val rebound =
            updateState { current ->
                current.copy(
                    pendingSessionId = sessionId.takeIf { current.pendingAction != null },
                    pendingPkcs12SessionId = sessionId.takeIf { current.pendingPkcs12Uri != null },
                    sessionReady = true,
                )
            }
        val action = rebound.pendingAction
        val uri = rebound.pickedDocumentUri
        val reboundSessionId = rebound.pendingSessionId
        if (action != null && uri != null && reboundSessionId != null) {
            if (importDocument(action, uri, reboundSessionId)) {
                clearPendingDocument()
            } else {
                updateState { it.copy(sessionReady = false) }
            }
        }
    }

    fun clear() {
        updateState { ConfigMasqueImportState() }
    }

    private fun clearPendingDocument() {
        updateState { current ->
            current.copy(
                pendingAction = null,
                pendingSessionId = null,
                pickedDocumentUri = null,
                recoveryOwnerId = current.recoveryOwnerId.takeIf { current.pendingPkcs12Uri != null },
            )
        }
    }

    private fun importDocument(
        action: MasqueImportAction,
        uri: Uri,
        sessionId: Long,
    ): Boolean =
        when (action) {
            MasqueImportAction.CertificateChain -> importCertificateChain(uri, sessionId)
            MasqueImportAction.PrivateKey -> importPrivateKey(uri, sessionId)
            MasqueImportAction.Pkcs12 -> false
        }

    private fun updateState(transform: (ConfigMasqueImportState) -> ConfigMasqueImportState): ConfigMasqueImportState =
        transform(mutableState.value).also { updated ->
            mutableState.value = updated
            onStateChanged(updated)
        }
}

private val ConfigMasqueImportState.hasPendingImport: Boolean
    get() = pendingAction != null || pickedDocumentUri != null || pendingPkcs12Uri != null

internal fun restoreConfigMasqueImportState(savedStateHandle: SavedStateHandle): ConfigMasqueImportState {
    val action =
        savedStateHandle
            .get<String>(MasqueImportActionSavedStateKey)
            ?.let { name -> runCatching { MasqueImportAction.valueOf(name) }.getOrNull() }
    val sessionId = savedStateHandle.get<Long>(MasqueImportSessionSavedStateKey)
    val recoveryOwnerId = savedStateHandle.get<String>(MasqueImportOwnerSavedStateKey)?.takeIf(String::isNotBlank)
    val pickedDocumentUri =
        savedStateHandle
            .get<String>(MasquePickedDocumentUriSavedStateKey)
            ?.takeIf(String::isNotBlank)
            ?.let(Uri::parse)
    val pkcs12Uri =
        savedStateHandle
            .get<String>(MasquePkcs12UriSavedStateKey)
            ?.takeIf(String::isNotBlank)
            ?.let(Uri::parse)
    val pkcs12SessionId = savedStateHandle.get<Long>(MasquePkcs12SessionSavedStateKey)
    return ConfigMasqueImportState(
        pendingAction = action.takeIf { sessionId != null },
        pendingSessionId = sessionId.takeIf { action != null },
        recoveryOwnerId = recoveryOwnerId,
        pickedDocumentUri = pickedDocumentUri.takeIf { action != null && sessionId != null },
        pendingPkcs12Uri = pkcs12Uri.takeIf { pkcs12SessionId != null },
        pendingPkcs12SessionId = pkcs12SessionId.takeIf { pkcs12Uri != null },
    )
}

internal fun persistConfigMasqueImportState(
    savedStateHandle: SavedStateHandle,
    state: ConfigMasqueImportState,
) {
    savedStateHandle.putOrRemove(MasqueImportActionSavedStateKey, state.pendingAction?.name)
    savedStateHandle.putOrRemove(MasqueImportSessionSavedStateKey, state.pendingSessionId)
    savedStateHandle.putOrRemove(MasqueImportOwnerSavedStateKey, state.recoveryOwnerId)
    savedStateHandle.putOrRemove(MasquePickedDocumentUriSavedStateKey, state.pickedDocumentUri?.toString())
    savedStateHandle.putOrRemove(MasquePkcs12UriSavedStateKey, state.pendingPkcs12Uri?.toString())
    savedStateHandle.putOrRemove(MasquePkcs12SessionSavedStateKey, state.pendingPkcs12SessionId)
}

private fun <T> SavedStateHandle.putOrRemove(
    key: String,
    value: T?,
) {
    if (value == null) remove<T>(key) else set(key, value)
}
