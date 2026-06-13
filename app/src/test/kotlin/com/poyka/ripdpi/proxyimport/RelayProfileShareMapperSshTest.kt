package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSH coverage for [RelayProfileShareMapper]: a saved SSH relay record plus its
 * secure credentials must reconstruct a [ProxyProfile.Ssh] and a canonical
 * `ssh://` share URI, and must refuse to share when the auth-relevant secret is
 * absent. The `ssh://` codec round-trip itself is covered by `ProxyUriCodecTest`.
 */
class RelayProfileShareMapperSshTest {
    private val fingerprint = "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg"

    // Named *Fixture so the no-secrets pre-commit hook excludes these lines.
    private val passwordFixture = "ssh-relay-fixture-secret"
    private val privateKeyFixture =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nfixtureAAAA\n-----END OPENSSH PRIVATE KEY-----"

    @Test
    fun `password ssh record maps to a shareable ssh profile`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindSsh,
                server = "ssh.example",
                serverPort = 22,
                sshAuthType = RelaySshAuthTypePassword,
                sshHostKeyFingerprint = fingerprint,
                sshStrictHostKey = true,
            )
        val credentials =
            RelayCredentialRecord(
                profileId = record.id,
                sshUsername = "alice",
                sshPassword = passwordFixture,
            )

        val shareable = RelayProfileShareMapper.toShareable(record, credentials)

        assertNotNull(shareable)
        val profile = shareable!!.profile as ProxyProfile.Ssh
        assertEquals("ssh.example", profile.server)
        assertEquals(22, profile.serverPort)
        assertEquals("alice", profile.username)
        assertEquals(RelaySshAuthTypePassword, profile.authType)
        assertEquals(passwordFixture, profile.password)
        assertNull(profile.privateKey)
        assertEquals(fingerprint, profile.hostKeyFingerprint)
        assertTrue(profile.strictHostKey)
        assertTrue("share URI must use the ssh scheme", shareable.shareUri.startsWith("ssh://"))
    }

    @Test
    fun `private-key ssh record maps with the key and drops the password`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindSsh,
                server = "ssh.example",
                serverPort = 2222,
                sshAuthType = RelaySshAuthTypePrivateKey,
            )
        val credentials =
            RelayCredentialRecord(
                profileId = record.id,
                sshUsername = "bob",
                sshPrivateKey = privateKeyFixture,
                sshPrivateKeyPassphrase = "pp",
            )

        val profile = RelayProfileShareMapper.toShareable(record, credentials)?.profile as? ProxyProfile.Ssh

        assertNotNull(profile)
        assertEquals(RelaySshAuthTypePrivateKey, profile!!.authType)
        assertNull(profile.password)
        assertEquals("pp", profile.privateKeyPassphrase)
        assertTrue(profile.privateKey.orEmpty().contains("OPENSSH PRIVATE KEY"))
    }

    @Test
    fun `ssh record without a username is not shareable`() {
        val record = RelayProfileRecord(kind = RelayKindSsh, server = "ssh.example", serverPort = 22)
        assertNull(RelayProfileShareMapper.toShareable(record, RelayCredentialRecord(profileId = record.id)))
    }

    @Test
    fun `password-auth ssh record missing the password is not shareable`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindSsh,
                server = "ssh.example",
                serverPort = 22,
                sshAuthType = RelaySshAuthTypePassword,
            )
        val credentials = RelayCredentialRecord(profileId = record.id, sshUsername = "alice")
        assertNull(RelayProfileShareMapper.toShareable(record, credentials))
    }
}
