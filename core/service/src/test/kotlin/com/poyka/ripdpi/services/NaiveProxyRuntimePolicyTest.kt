package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
                localSocksUsername = "local-user",
                localSocksPassword = "local-pass", // Fixture credential.
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
        assertFalse(args.contains("local-user"))
        assertFalse(args.contains("local-pass"))
    }

    @Test
    fun `manager writes naive credentials to stdin payload`() {
        val config =
            sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
                localSocksUsername = "local-user",
                localSocksPassword = "local-pass", // Fixture credential.
                naiveUsername = "alice",
                naivePassword = "secret",
            )

        assertEquals(
            "YWxpY2U=\nc2VjcmV0\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n\n",
            naiveProxyCredentialsStdin(config).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `empty password stays an authenticated identity distinct from absent auth`() {
        val base =
            sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
                localSocksUsername = "local-user",
                localSocksPassword = "local-pass", // Fixture credential.
            )

        // base64("alice"), empty password line.
        assertEquals(
            "YWxpY2U=\n\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\n\n",
            naiveProxyCredentialsStdin(base.copy(naiveUsername = "alice", naivePassword = "")).toString(Charsets.UTF_8),
        )
        val emptyPassword = RelayCredentialRecord(profileId = "p", naiveUsername = "alice", naivePassword = "")
        validateNaiveRelayCredentials("p", emptyPassword)
        assertThrows(IllegalArgumentException::class.java) {
            validateNaiveRelayCredentials("p", RelayCredentialRecord(profileId = "p", naiveUsername = "alice"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateNaiveRelayCredentials("p", RelayCredentialRecord(profileId = "p", naivePassword = "x"))
        }
    }

    @Test
    fun `extra headers travel as the fifth stdin line`() {
        val config =
            sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
                localSocksUsername = "local-user",
                localSocksPassword = "local-pass", // Fixture credential.
                naiveUsername = "alice",
                naivePassword = "secret",
                naiveExtraHeaders = "X-Token: s3cret\r\nX-Other: v",
            )

        // base64("X-Token: s3cret\r\nX-Other: v")
        assertEquals(
            "YWxpY2U=\nc2VjcmV0\nbG9jYWwtdXNlcg==\nbG9jYWwtcGFzcw==\nWC1Ub2tlbjogczNjcmV0DQpYLU90aGVyOiB2\n",
            naiveProxyCredentialsStdin(config).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `manager rejects absent local listener credentials`() {
        val config =
            sampleResolvedRelayConfig(kind = RelayKindNaiveProxy).copy(
                naiveUsername = null,
                naivePassword = null,
            )

        assertThrows(IllegalArgumentException::class.java) { naiveProxyCredentialsStdin(config) }
    }

    @Test
    fun `clean exit is not restarted`() {
        val decision = naiveProxyRestartDecision(exitCode = 0)

        assertFalse(decision.shouldRestart)
        assertEquals("clean_exit", decision.reasonLabel)
    }

    @Test
    fun `crash exits stay retryable`() {
        listOf(1, 2, 134, -1).forEach { exitCode ->
            assertTrue("exit $exitCode must restart", naiveProxyRestartDecision(exitCode).shouldRestart)
        }
    }
}
