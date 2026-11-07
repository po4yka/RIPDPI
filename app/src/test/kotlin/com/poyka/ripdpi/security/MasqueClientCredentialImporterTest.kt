package com.poyka.ripdpi.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MasqueClientCredentialImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val importer =
        AndroidMasqueClientCredentialImporter(
            context = context,
            dispatchers =
                AppCoroutineDispatchers(
                    default = dispatcher,
                    io = dispatcher,
                    main = dispatcher,
                ),
        )

    @Test
    fun importCertificateChainPemAcceptsDerAndPemWithTheSameContents() =
        runTest(dispatcher) {
            val certificate = HeldCertificate.Builder().commonName("client.fixture.test").build()
            val fromDer = importer.importCertificateChainPem(writeDocument(certificate.certificate.encoded))
            val fromPem = importer.importCertificateChainPem(writeDocument(certificate.certificatePem().toByteArray()))

            assertEquals(certificate.certificatePem().trim(), fromDer)
            assertEquals(fromDer, fromPem)
        }

    @Test
    fun importCertificateChainPemKeepsTheOrderedPemChain() =
        runTest(dispatcher) {
            val root =
                HeldCertificate
                    .Builder()
                    .commonName("root.fixture.test")
                    .certificateAuthority(1)
                    .build()
            val client =
                HeldCertificate
                    .Builder()
                    .commonName("client.fixture.test")
                    .signedBy(root)
                    .build()
            val input = client.certificatePem() + root.certificatePem()

            val normalized = importer.importCertificateChainPem(writeDocument(input.toByteArray()))
            val certificates =
                CertificateFactory
                    .getInstance("X.509")
                    .generateCertificates(ByteArrayInputStream(normalized.toByteArray()))
                    .toList()

            assertEquals(2, certificates.size)
            assertArrayEquals(client.certificate.encoded, certificates[0].encoded)
            assertArrayEquals(root.certificate.encoded, certificates[1].encoded)
        }

    @Test
    fun importCertificateChainPemAcceptsTheExactSizeLimit() =
        runTest(dispatcher) {
            val certificate = HeldCertificate.Builder().commonName("client.fixture.test").build()
            val bytes = certificate.certificatePem().padEnd(256 * 1024, '\n').toByteArray()

            assertEquals(
                certificate.certificatePem().trim(),
                importer.importCertificateChainPem(writeDocument(bytes)),
            )
        }

    @Test
    fun importCertificateChainPemRejectsEmptyAndMalformedDocuments() =
        runTest(dispatcher) {
            val payload = "fixture malformed certificate payload"
            val certificate = HeldCertificate.Builder().commonName("client.fixture.test").build()
            for (bytes in listOf(byteArrayOf(), payload.toByteArray(), certificate.certificate.encoded.copyOf(20))) {
                try {
                    importer.importCertificateChainPem(writeDocument(bytes))
                    fail("Expected certificate rejection")
                } catch (error: Exception) {
                    assertTrue(error is IllegalArgumentException || error is CertificateException)
                    assertFalse(error.message.orEmpty().contains(payload))
                }
            }
        }

    @Test
    fun importCertificateChainPemRejectsOversizedDocument() =
        runTest(dispatcher) {
            val uri = writeDocument(ByteArray(256 * 1024 + 1) { 'A'.code.toByte() })

            val error =
                assertIllegalArgument {
                    importer.importCertificateChainPem(uri)
                }

            assertTrue(error.message.orEmpty().contains("too large"))
        }

    @Test
    fun importPrivateKeyPemRejectsMalformedUtf8() =
        runTest(dispatcher) {
            val uri = writeDocument(byteArrayOf(0x2d, 0x2d, 0xc3.toByte()))

            val error =
                assertIllegalArgument {
                    importer.importPrivateKeyPem(uri)
                }

            assertTrue(error.message.orEmpty().contains("valid UTF-8"))
        }

    private fun writeDocument(bytes: ByteArray): Uri {
        val file = File(context.cacheDir, "masque-import-${System.nanoTime()}.pem")
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private suspend fun assertIllegalArgument(block: suspend () -> Unit): IllegalArgumentException =
        try {
            block()
            fail("Expected IllegalArgumentException")
            error("unreachable")
        } catch (error: IllegalArgumentException) {
            error
        }
}
