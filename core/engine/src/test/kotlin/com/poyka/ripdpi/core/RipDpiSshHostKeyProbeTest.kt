package com.poyka.ripdpi.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiSshHostKeyProbeTest {
    @Test
    fun `successful status with incomplete output fails closed`() {
        val bindings =
            FakeRipDpiSshHostKeyBindings().apply {
                resultCode = 0
                fingerprintSha256 = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            }

        val result =
            RipDpiSshHostKeyProbe(bindings).probe(
                SshHostKeyProbeRequest("127.0.0.1", 22),
                SshProbeSocketController { false },
            )

        assertEquals(SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.InternalFailure), result)
    }

    @Test
    fun `complete successful native output becomes observed host key`() {
        val fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val bindings =
            FakeRipDpiSshHostKeyBindings().apply {
                resultCode = 0
                fingerprintSha256 = fingerprint
                algorithm = "ssh-ed25519"
            }

        val result =
            RipDpiSshHostKeyProbe(bindings).probe(
                SshHostKeyProbeRequest("127.0.0.1", 22, 1_000),
                SshProbeSocketController { false },
            )

        assertEquals(SshHostKeyProbeResult.Observed(fingerprint, "ssh-ed25519"), result)
    }
}
