package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyManagementException
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.TrustManager

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TlsFingerprintCheckerTest {
    @Test
    fun `native default input is normalized to chrome stable in findings`() =
        runTest {
            val result = TlsFingerprintChecker.check(testDispatchers(), "native_default")

            assertTrue(
                result.findings.any { finding -> finding.description == "Fingerprint profile: chrome_stable" },
            )
            assertTrue(
                result.findings.any { finding -> finding.description == "Using Chrome-stable TLS profile" },
            )
        }

    @Test
    fun `provider initialization failure has no TLS hash or positive result`() =
        runTest {
            val provider =
                object : Provider("UnavailableLocalTlsTest", 1.0, "TLS test provider") {
                    init {
                        put("SSLContext.TLS", UnavailableTls::class.java.name)
                    }
                }
            assertEquals(1, Security.insertProviderAt(provider, 1))

            val result =
                try {
                    TlsFingerprintChecker.check(testDispatchers())
                } finally {
                    Security.removeProvider(provider.name)
                }

            assertEquals(CheckExecutionStatus.ERROR, result.executionStatus)
            assertUnavailable(result)
        }

    @Test
    fun `empty protocols or ciphers have no TLS hash or positive result`() {
        val cipher = "TLS_AES_128_GCM_SHA256"
        val noCiphers = TlsFingerprintChecker.summarizeLocalTls("chrome_stable", emptyList(), listOf("TLSv1.3"))
        val noProtocols = TlsFingerprintChecker.summarizeLocalTls("chrome_stable", listOf(cipher), emptyList())

        for (result in listOf(noCiphers, noProtocols)) {
            assertEquals(CheckExecutionStatus.INCONCLUSIVE, result.executionStatus)
            assertUnavailable(result)
        }
    }

    private fun assertUnavailable(result: CategoryResult) {
        assertFalse(result.detected)
        assertFalse(result.needsReview)
        assertTrue(result.evidence.isEmpty())
        assertEquals("Local TLS configuration is unavailable", result.findings.single().description)
    }

    private fun testDispatchers(): AppCoroutineDispatchers {
        val dispatcher = UnconfinedTestDispatcher()
        return AppCoroutineDispatchers(
            default = dispatcher,
            io = dispatcher,
            main = dispatcher,
        )
    }

    class UnavailableTls : SSLContextSpi() {
        override fun engineInit(
            managers: Array<out KeyManager>?,
            trustManagers: Array<out TrustManager>?,
            random: SecureRandom?,
        ): Unit = throw KeyManagementException("TLS initialization failed")

        override fun engineGetSocketFactory() = error("TLS is unavailable")

        override fun engineGetServerSocketFactory() = error("TLS is unavailable")

        override fun engineCreateSSLEngine() = error("TLS is unavailable")

        override fun engineCreateSSLEngine(
            host: String?,
            port: Int,
        ) = error("TLS is unavailable")

        override fun engineGetServerSessionContext() = error("TLS is unavailable")

        override fun engineGetClientSessionContext() = error("TLS is unavailable")
    }
}
