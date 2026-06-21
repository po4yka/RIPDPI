package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindNaiveProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaiveProxyRuntimePolicyTest {
    @Test
    fun `manager command arguments do not expose credentials in argv`() {
        val config =
            sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
                server = "proxy.example",
                serverPort = 8443,
                serverName = "sni.example",
                localSocksHost = "127.0.0.2",
                localSocksPort = 11980,
                naiveUsername = "alice",
                naivePassword = "secret",
                naivePath = "/proxy",
            )

        val args = naiveProxyCommandArguments(config)

        assertEquals(
            listOf(
                "--listen",
                "127.0.0.2:11980",
                "--server",
                "proxy.example",
                "--server-port",
                "8443",
                "--server-name",
                "sni.example",
                "--credentials-stdin",
                "--path",
                "/proxy",
            ),
            args,
        )
        assertFalse(args.contains("alice"))
        assertFalse(args.contains("secret"))
    }

    @Test
    fun `manager writes naive credentials to stdin payload`() {
        val config =
            sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
                naiveUsername = "alice",
                naivePassword = "secret",
            )

        assertEquals("YWxpY2U=\nc2VjcmV0\n", naiveProxyCredentialsStdin(config)?.toString(Charsets.UTF_8))
    }

    @Test
    fun `manager omits credential stdin payload when auth is absent`() {
        val config =
            sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
                naiveUsername = null,
                naivePassword = null,
            )

        assertNull(naiveProxyCredentialsStdin(config))
    }

    @Test
    fun `clean exit is not restarted`() {
        val decision = naiveProxyRestartDecision(exitCode = 0, lastFailureClass = null)

        assertFalse(decision.shouldRestart)
        assertEquals("clean_exit", decision.reasonLabel)
    }

    @Test
    fun `auth and config failures do not restart`() {
        listOf("auth", "http_connect", "tls", "config").forEach { failureClass ->
            val decision = naiveProxyRestartDecision(exitCode = 1, lastFailureClass = failureClass)

            assertFalse("expected $failureClass to be terminal", decision.shouldRestart)
            assertEquals(failureClass, decision.reasonLabel)
        }
    }

    @Test
    fun `dns failures restart with slower delay`() {
        val decision = naiveProxyRestartDecision(exitCode = 1, lastFailureClass = "dns")

        assertTrue(decision.shouldRestart)
        assertEquals(1_500L, decision.delayMillis)
        assertEquals("dns", decision.reasonLabel)
    }

    @Test
    fun `transport and unknown failures stay retryable`() {
        listOf("connect", "runtime", "helper_exit", null).forEach { failureClass ->
            val decision = naiveProxyRestartDecision(exitCode = 2, lastFailureClass = failureClass)

            assertTrue("expected $failureClass to remain retryable", decision.shouldRestart)
        }
    }
}
