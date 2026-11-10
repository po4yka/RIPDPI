package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshProfileAdmissionTest {
    private val valid =
        ProxyProfile.Ssh(
            "ssh",
            "SSH",
            "",
            "ssh.example",
            22,
            "alice",
            password = "fixture",
            hostKeyFingerprint = "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg",
        )

    @Test
    fun `admission rejects invalid endpoint auth and host key before activation`() {
        val invalid =
            listOf(
                valid.copy(server = " "),
                valid.copy(serverPort = 0),
                valid.copy(serverPort = 65536),
                valid.copy(username = " "),
                valid.copy(authType = "unsupported"),
                valid.copy(password = null),
                valid.copy(password = ""),
                valid.copy(authType = RelaySshAuthTypePrivateKey),
                valid.copy(hostKeyFingerprint = null),
                valid.copy(hostKeyFingerprint = ""),
                valid.copy(hostKeyFingerprint = "SHA256:AAAA"),
                valid.copy(hostKeyFingerprint = "SHA256:n4bQgYhMfWWaL-qgxVrQFaO/TxsrC4Is0V1sFbDwCgg"),
                valid.copy(hostKeyFingerprint = "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgh"),
            )
        invalid.forEach { profile ->
            assertTrue(profile.toString(), validateNativeRelayProfileResult(profile).isFailure)
        }
    }

    @Test
    fun `admission accepts both auth modes and canonical fingerprint spellings`() {
        val key = valid.copy(authType = RelaySshAuthTypePrivateKey, password = null, privateKey = "PEM fixture")
        for (profile in listOf(valid, valid.copy(password = " "), key, key.copy(privateKeyPassphrase = " "))) {
            for (strict in listOf(false, true)) {
                for (fingerprint in listOf(
                    valid.hostKeyFingerprint,
                    "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=",
                    "SHA256:n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg",
                )) {
                    assertTrue(
                        validateNativeRelayProfileResult(
                            profile.copy(strictHostKey = strict, hostKeyFingerprint = fingerprint),
                        ).isSuccess,
                    )
                }
            }
        }
    }

    @Test
    fun `URI keeps unpinned profiles editable but rejects unknown auth and invalid ports`() {
        assertNotNull(ProxyUriCodec.parse("ssh://alice:fixture@ssh.example:22"))
        assertNull(ProxyUriCodec.parse("ssh://alice:fixture@ssh.example:65536"))
        assertNull(ProxyUriCodec.parse("ssh://alice:fixture@ssh.example:22?auth=unsupported"))
    }
}
