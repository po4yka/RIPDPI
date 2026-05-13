package com.poyka.ripdpi.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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
