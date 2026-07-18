package com.poyka.ripdpi.activities

import android.net.Uri
import com.poyka.ripdpi.R
import com.poyka.ripdpi.security.ImportedMasqueClientIdentity
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class ConfigMasqueImportController(
    private val scope: CoroutineScope,
    private val importer: MasqueClientCredentialImporter,
    private val beginOperation: (Long, Boolean, Boolean) -> MasqueImportOperation?,
    private val applyDraft: (MasqueImportOperation, ConfigDraft.() -> ConfigDraft) -> Boolean,
    private val reportFailure: (MasqueImportOperation, Throwable, Int) -> Unit,
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
    ) {
        val operation = beginOperation(sessionId, certificate, privateKey) ?: return
        scope.launch {
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
        }
    }
}

private fun ConfigDraft.withImportedMasqueIdentity(identity: ImportedMasqueClientIdentity): ConfigDraft =
    copy(
        relayMasqueClientCertificateChainPem = identity.certificateChainPem,
        relayMasqueClientPrivateKeyPem = identity.privateKeyPem,
    )
