package com.poyka.ripdpi.activities

import android.net.Uri
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class ConfigMasqueCredentialImportRunner(
    private val scope: CoroutineScope,
    private val importer: MasqueClientCredentialImporter,
    private val beginOperation: (Long, Boolean, Boolean) -> MasqueImportOperation?,
    private val applyDraft: (MasqueImportOperation, ConfigDraft.() -> ConfigDraft) -> Boolean,
    private val reportFailure: (MasqueImportOperation, Throwable, Int) -> Unit,
    private val finishOperation: (MasqueImportOperation) -> Unit,
) {
    fun importCertificateChain(
        uri: Uri,
        sessionId: Long,
    ) = importCredential(
        sessionId = sessionId,
        certificate = true,
        errorResource = R.string.config_import_certificate_failed,
        load = { importer.importCertificateChainPem(uri) },
        transform = { certificateChain -> copy(relayMasqueClientCertificateChainPem = certificateChain) },
    )

    fun importPrivateKey(
        uri: Uri,
        sessionId: Long,
    ) = importCredential(
        sessionId = sessionId,
        privateKey = true,
        errorResource = R.string.config_import_private_key_failed,
        load = { importer.importPrivateKeyPem(uri) },
        transform = { privateKey -> copy(relayMasqueClientPrivateKeyPem = privateKey) },
    )

    fun importPkcs12(
        uri: Uri,
        password: String?,
        sessionId: Long,
    ) = importCredential(
        sessionId = sessionId,
        certificate = true,
        privateKey = true,
        errorResource = R.string.config_import_pkcs12_failed,
        load = { importer.importPkcs12Identity(uri, password) },
        transform = ConfigDraft::withImportedMasqueIdentity,
    )

    private fun <T> importCredential(
        sessionId: Long,
        certificate: Boolean = false,
        privateKey: Boolean = false,
        errorResource: Int,
        load: suspend () -> T,
        transform: ConfigDraft.(T) -> ConfigDraft,
    ): Boolean {
        val operation = beginOperation(sessionId, certificate, privateKey) ?: return false
        scope.launch {
            try {
                val result = runCatching { load() }
                val error = result.exceptionOrNull()
                when {
                    error is CancellationException -> {
                        throw error
                    }

                    error != null -> {
                        reportFailure(operation, error, errorResource)
                    }

                    else -> {
                        applyDraft(operation) { transform(result.getOrThrow()) }
                    }
                }
            } finally {
                finishOperation(operation)
            }
        }
        return true
    }
}

internal class ConfigMasqueImportSessionCoordinator(
    private val editorSession: MutableStateFlow<ConfigEditorSession>,
    private val operationLock: Any,
    private val generations: ConfigMasqueImportGenerationTracker,
    private val log: Logger,
    private val effects: MutableSharedFlow<ConfigEffect>,
    private val stringResolver: StringResolver,
) {
    private val _inFlightOperations = MutableStateFlow<Set<MasqueImportOperation>>(emptySet())
    val inFlightOperations: StateFlow<Set<MasqueImportOperation>> = _inFlightOperations.asStateFlow()

    fun begin(
        expectedSessionId: Long,
        certificate: Boolean = false,
        privateKey: Boolean = false,
    ): MasqueImportOperation? =
        synchronized(operationLock) {
            if (!isCurrentEditorSession(expectedSessionId)) {
                null
            } else {
                generations
                    .begin(
                        sessionId = expectedSessionId,
                        certificate = certificate,
                        privateKey = privateKey,
                    ).also { operation ->
                        _inFlightOperations.value += operation
                    }
            }
        }

    fun finish(operation: MasqueImportOperation) {
        synchronized(operationLock) {
            _inFlightOperations.value -= operation
        }
    }

    fun hasInFlightImport(sessionId: Long): Boolean =
        synchronized(operationLock) {
            _inFlightOperations.value.any { operation -> operation.sessionId == sessionId }
        }

    fun updateDraft(
        operation: MasqueImportOperation,
        transform: ConfigDraft.() -> ConfigDraft,
    ): Boolean =
        synchronized(operationLock) {
            if (isCurrentImport(operation)) {
                updateConfigDraftForSession(editorSession, operation.sessionId, transform)
            } else {
                false
            }
        }

    fun reportFailure(
        operation: MasqueImportOperation,
        error: Throwable,
        messageResource: Int,
    ) {
        synchronized(operationLock) {
            if (isCurrentImport(operation)) {
                log.e(error) { "Failed to import MASQUE client credential" }
                effects.tryEmit(ConfigEffect.Message(stringResolver.getString(messageResource)))
            }
        }
    }

    private fun isCurrentImport(operation: MasqueImportOperation): Boolean =
        isCurrentEditorSession(operation.sessionId) && generations.isCurrent(operation)

    private fun isCurrentEditorSession(expectedSessionId: Long): Boolean =
        editorSession.value.let { current ->
            current.sessionId == expectedSessionId &&
                current.presetId != null &&
                !current.hydrationPending &&
                !current.savePending
        }
}

private fun updateConfigDraftForSession(
    editorSession: MutableStateFlow<ConfigEditorSession>,
    expectedSessionId: Long,
    transform: ConfigDraft.() -> ConfigDraft,
): Boolean {
    while (true) {
        val current = editorSession.value
        if (current.sessionId != expectedSessionId || current.presetId == null || current.hydrationPending) {
            return false
        }
        val updated =
            current.copy(
                draft = requireNotNull(current.draft).applyRelayDraftEdit(transform),
                draftRevision = current.draftRevision + 1,
            )
        if (editorSession.compareAndSet(current, updated)) return true
    }
}

private fun ConfigDraft.withImportedMasqueIdentity(identity: ImportedMasqueClientIdentity): ConfigDraft =
    copy(
        relayMasqueClientCertificateChainPem = identity.certificateChainPem,
        relayMasqueClientPrivateKeyPem = identity.privateKeyPem,
    )
