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
    fun `configured profiles change only the label of the local TLS configuration`() =
        runTest {
            val chrome = TlsFingerprintChecker.check(testDispatchers(), "native_default")
            val firefox = TlsFingerprintChecker.check(testDispatchers(), "firefox_stable")

            assertEquals(CheckExecutionStatus.COMPLETED, chrome.executionStatus)
            assertTrue(chrome.findings.any { it.description == "Configured TLS profile: chrome_stable" })
            assertTrue(firefox.findings.any { it.description == "Configured TLS profile: firefox_stable" })
            assertEquals(
                chrome.findings.filterNot { it.description.startsWith("Configured TLS profile:") },
                firefox.findings.filterNot { it.description.startsWith("Configured TLS profile:") },
            )
        }

    @Test
    fun `reference cipher coverage counts distinct matches and creates only local review`() {
        val referenceCiphers =
            listOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            )
        val testCases = listOf(0 to true, 4 to true, 5 to false, 9 to false)

        for ((coverage, review) in testCases) {
            val extraCipher = "TLS_RSA_WITH_AES_128_GCM_SHA256"
            val ciphers = referenceCiphers.take(coverage).let { it + it + extraCipher }
            val result = TlsFingerprintChecker.summarizeLocalTls("chrome_stable", ciphers, listOf("TLSv1.3"))

            assertEquals(CheckExecutionStatus.COMPLETED, result.executionStatus)
            assertTrue(result.findings.any { it.description == "Enabled browser reference cipher suites: $coverage/9" })
            assertEquals(review, result.needsReview)
            assertFalse(result.detected)
            assertTrue(result.findings.none { it.detected || it.source != null })
            assertTrue(result.evidence.isEmpty())
        }
    }

    @Test
    fun `disabled modern TLS is described as local socket configuration`() {
        val result =
            TlsFingerprintChecker.summarizeLocalTls(
                "chrome_stable",
                listOf("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"),
                listOf("TLSv1.2"),
            )

        assertTrue(
            result.findings.any {
                it.description == "TLS 1.3 is not enabled in the local TLS socket" && it.needsReview
            },
        )
        assertFalse(result.detected)
        assertTrue(result.evidence.isEmpty())
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
