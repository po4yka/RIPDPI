package com.poyka.ripdpi.security

import android.content.Context
import android.net.Uri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val PemLineWidth = 64
private val PemLineSeparator = "\n".toByteArray()

data class ImportedMasqueClientIdentity(
    val certificateChainPem: String,
    val privateKeyPem: String,
)

interface MasqueClientCredentialImporter {
    suspend fun importCertificateChainPem(uri: Uri): String

    suspend fun importPrivateKeyPem(uri: Uri): String

    suspend fun importPkcs12Identity(
        uri: Uri,
        password: String?,
    ): ImportedMasqueClientIdentity
}

@Singleton
class AndroidMasqueClientCredentialImporter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : MasqueClientCredentialImporter {
        override suspend fun importCertificateChainPem(uri: Uri): String =
            withContext(Dispatchers.IO) {
                normalizeCertificatePem(readDocument(uri))
            }

        override suspend fun importPrivateKeyPem(uri: Uri): String =
            withContext(Dispatchers.IO) {
                normalizePrivateKeyPem(readDocument(uri))
            }

        override suspend fun importPkcs12Identity(
            uri: Uri,
            password: String?,
        ): ImportedMasqueClientIdentity =
            withContext(Dispatchers.IO) {
                val keyStore = KeyStore.getInstance("PKCS12")
                keyStore.load(ByteArrayInputStream(readDocument(uri)), password?.toCharArray())
                val alias =
                    keyStore.aliasSequence().firstOrNull { candidate ->
                        keyStore.isKeyEntry(candidate)
                    } ?: throw IllegalArgumentException("The PKCS#12 bundle does not contain a private key entry.")
                val key =
                    keyStore.getKey(alias, password?.toCharArray()) as? PrivateKey
                        ?: throw IllegalArgumentException("The PKCS#12 bundle private key could not be read.")
                val chain =
                    keyStore.getCertificateChain(alias)?.toList().orEmpty().ifEmpty {
                        listOfNotNull(keyStore.getCertificate(alias))
                    }
                require(chain.isNotEmpty()) {
                    "The PKCS#12 bundle does not contain a certificate chain."
                }
                ImportedMasqueClientIdentity(
                    certificateChainPem = chain.joinToString(separator = "\n") { certificateToPem(it) },
                    privateKeyPem = privateKeyToPem(key),
                )
            }

        private fun readDocument(uri: Uri): ByteArray =
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes()
            } ?: throw IllegalArgumentException("Unable to read the selected document.")

        private fun normalizeCertificatePem(bytes: ByteArray): String {
            val text = bytes.toString(Charsets.UTF_8)
            val pemBlocks = CertificatePemRegex.findAll(text).map { it.groupValues[1] }.toList()
            if (pemBlocks.isNotEmpty()) {
                return pemBlocks.joinToString(separator = "\n") { block ->
                    val certificate =
                        certificateFactory().generateCertificate(
                            ByteArrayInputStream(Base64.getMimeDecoder().decode(block)),
                        ) as X509Certificate
                    certificateToPem(certificate)
                }
            }

            val certificates = certificateFactory().generateCertificates(ByteArrayInputStream(bytes)).toList()
            require(certificates.isNotEmpty()) {
                "The selected certificate file did not contain any X.509 certificates."
            }
            return certificates.joinToString(separator = "\n") { certificateToPem(it) }
        }

        private fun normalizePrivateKeyPem(bytes: ByteArray): String {
            val text = bytes.toString(Charsets.UTF_8)
            val match =
                PrivateKeyPemRegex.find(text)
                    ?: throw IllegalArgumentException("The selected key file must be a PEM private key.")
            val label = match.groupValues[1].trim()
            val payload = match.groupValues[2]
            require(label.contains("PRIVATE KEY")) {
                "The selected key file must contain a PEM private key."
            }
            val normalizedPayload = payload.filterNot(Char::isWhitespace)
            Base64.getDecoder().decode(normalizedPayload)
            return buildPemBlock(label, normalizedPayload)
        }

        private fun certificateFactory(): CertificateFactory = CertificateFactory.getInstance("X.509")

        private fun certificateToPem(certificate: Certificate): String =
            buildPemBlock(
                label = "CERTIFICATE",
                payload = Base64.getMimeEncoder(PemLineWidth, PemLineSeparator).encodeToString(certificate.encoded),
                alreadyWrapped = true,
            )

        private fun privateKeyToPem(privateKey: PrivateKey): String =
            buildPemBlock(
                label = "PRIVATE KEY",
                payload = Base64.getMimeEncoder(PemLineWidth, PemLineSeparator).encodeToString(privateKey.encoded),
                alreadyWrapped = true,
            )

        private fun buildPemBlock(
            label: String,
            payload: String,
            alreadyWrapped: Boolean = false,
        ): String {
            val body =
                if (alreadyWrapped) {
                    payload.trim()
                } else {
                    payload.chunked(PemLineWidth).joinToString(separator = "\n")
                }
            return "-----BEGIN $label-----\n$body\n-----END $label-----"
        }

        private companion object {
            val CertificatePemRegex =
                Regex(
                    "-----BEGIN CERTIFICATE-----\\s*(.*?)\\s*-----END CERTIFICATE-----",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
                )
            val PrivateKeyPemRegex =
                Regex(
                    "-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----\\s*(.*?)\\s*-----END \\1-----",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
                )
        }

        private fun KeyStore.aliasSequence(): Sequence<String> =
            sequence {
                val aliases = aliases()
                while (aliases.hasMoreElements()) {
                    yield(aliases.nextElement())
                }
            }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class MasqueClientCredentialImporterModule {
    @Binds
    @Singleton
    abstract fun bindMasqueClientCredentialImporter(
        importer: AndroidMasqueClientCredentialImporter,
    ): MasqueClientCredentialImporter
}
