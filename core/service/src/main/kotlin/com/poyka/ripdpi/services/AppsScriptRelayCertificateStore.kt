package com.poyka.ripdpi.services

import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Owns the persistent Apps Script CA location; the private key stays in app storage. */
class AppsScriptRelayCertificateStore(
    filesDirectory: File,
) {
    val dataDirectory: File = File(filesDirectory, "apps-script-relay")

    /** Return a DER certificate only. No caller can export the CA private key through this method. */
    fun publicCertificate(): ByteArray {
        val file = dataDirectory.resolve("relay-ca/ca.crt")
        require(file.length() in 1..65_536) { "Start the Apps Script relay before exporting its CA" }
        val certificate =
            file.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        require(certificate.basicConstraints >= 0) { "The stored certificate is not a CA" }
        return certificate.encoded
    }
}
