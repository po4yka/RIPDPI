package com.poyka.ripdpi.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.cert.CertificateFactory

class AppsScriptRelayCertificateStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `CA storage survives store recreation in app files`() {
        val first = AppsScriptRelayCertificateStore(directory.root).dataDirectory
        first.mkdirs()
        val marker = first.resolve("persistent-marker")
        marker.writeText("retained")
        val reopened = AppsScriptRelayCertificateStore(directory.root).dataDirectory
        assertEquals(directory.root.canonicalFile, reopened.parentFile.canonicalFile)
        assertEquals("retained", reopened.resolve("persistent-marker").readText())
    }

    @Test
    fun `export contains only the public certificate even when extra PEM material exists`() {
        val pem = javaClass.getResource("/fixtures/apps-script-ca.crt")!!.readText()
        val store = AppsScriptRelayCertificateStore(directory.root)
        val ca = store.dataDirectory.resolve("relay-ca").apply { mkdirs() }
        ca
            .resolve(
                "ca.crt",
            ).writeText(pem + "\n-----BEGIN PRIVATE KEY-----\nfixture-private-marker\n-----END PRIVATE KEY-----")
        ca.resolve("ca.key").writeText("separate-private-marker")
        val exported = store.publicCertificate()
        val independentlyParsed = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream())
        assertArrayEquals(independentlyParsed.encoded, exported)
        assertEquals("separate-private-marker", ca.resolve("ca.key").readText())
    }

    @Test
    fun `missing or invalid CA cannot be exported`() {
        val store = AppsScriptRelayCertificateStore(directory.root)
        assertThrows(IllegalArgumentException::class.java) { store.publicCertificate() }
        store.dataDirectory.resolve("relay-ca").mkdirs()
        store.dataDirectory.resolve("relay-ca/ca.crt").writeText("not a certificate")
        assertThrows(java.security.cert.CertificateException::class.java) { store.publicCertificate() }
    }
}
