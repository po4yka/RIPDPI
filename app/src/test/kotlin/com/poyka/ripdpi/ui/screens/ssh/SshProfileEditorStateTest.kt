package com.poyka.ripdpi.ui.screens.ssh

import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [SshProfileEditorState] — the branch-dense `toProfile()`
 * assembly (auth-secret dropping, blank→null normalization, display-name
 * fallback) and the `isComplete` gate.
 */
class SshProfileEditorStateTest {
    /** A complete password-auth editor (server, port, username, password all valid). */
    private fun passwordEditor(): SshProfileEditorState =
        SshProfileEditorState
            .initial()
            .updateField(SshEditorField.SERVER, "ssh.example")
            .updateField(SshEditorField.SERVER_PORT, "22")
            .updateField(SshEditorField.USERNAME, "alice")
            .updateField(SshEditorField.PASSWORD, "pw-value")

    @Test
    fun `password auth produces a password profile and no key material`() {
        val profile = passwordEditor().toProfile()

        assertNotNull(profile)
        assertEquals("ssh.example", profile!!.server)
        assertEquals(22, profile.serverPort)
        assertEquals("alice", profile.username)
        assertEquals(RelaySshAuthTypePassword, profile.authType)
        assertEquals("pw-value", profile.password)
        assertNull(profile.privateKey)
        assertNull(profile.privateKeyPassphrase)
    }

    @Test
    fun `private-key auth produces a key profile and drops the password`() {
        val profile =
            passwordEditor()
                .selectAuthType(RelaySshAuthTypePrivateKey)
                .updateField(SshEditorField.PRIVATE_KEY, "key-body")
                .updateField(SshEditorField.PRIVATE_KEY_PASSPHRASE, "pp")
                .toProfile()

        assertNotNull(profile)
        assertEquals(RelaySshAuthTypePrivateKey, profile!!.authType)
        assertEquals("key-body", profile.privateKey)
        assertEquals("pp", profile.privateKeyPassphrase)
        assertNull("password auth-irrelevant for key auth must be dropped", profile.password)
    }

    @Test
    fun `password auth drops a typed-then-abandoned private key`() {
        val profile =
            passwordEditor()
                .updateField(SshEditorField.PRIVATE_KEY, "stale-key")
                .toProfile()

        assertNotNull(profile)
        assertEquals("pw-value", profile!!.password)
        assertNull("a private key typed while on password auth must not survive", profile.privateKey)
    }

    @Test
    fun `blank pinned fingerprint becomes null and a real one is carried`() {
        val blank = passwordEditor().updateField(SshEditorField.HOST_KEY_FINGERPRINT, "   ").toProfile()
        assertNull(blank!!.hostKeyFingerprint)

        val pinned = passwordEditor().updateField(SshEditorField.HOST_KEY_FINGERPRINT, "SHA256:abc").toProfile()
        assertEquals("SHA256:abc", pinned!!.hostKeyFingerprint)
    }

    @Test
    fun `display name falls back to the server when left empty`() {
        assertEquals("ssh.example", passwordEditor().toProfile()!!.displayName)
        val named = passwordEditor().updateField(SshEditorField.DISPLAY_NAME, "My SSH").toProfile()
        assertEquals("My SSH", named!!.displayName)
    }

    @Test
    fun `strict host-key toggle is carried`() {
        assertFalse(passwordEditor().toProfile()!!.strictHostKey)
        assertTrue(passwordEditor().setStrictHostKey(true).toProfile()!!.strictHostKey)
    }

    @Test
    fun `incomplete editors produce no profile`() {
        // Fresh editor: no endpoint, no credential.
        assertNull(SshProfileEditorState.initial().toProfile())
        // Endpoint present but the (default) password is missing.
        val noCredential =
            SshProfileEditorState
                .initial()
                .updateField(SshEditorField.SERVER, "ssh.example")
                .updateField(SshEditorField.SERVER_PORT, "22")
                .updateField(SshEditorField.USERNAME, "alice")
        assertFalse(noCredential.isComplete)
        assertNull(noCredential.toProfile())
        // Key auth selected but no key supplied.
        assertNull(noCredential.selectAuthType(RelaySshAuthTypePrivateKey).toProfile())
        // Out-of-range port.
        assertNull(passwordEditor().updateField(SshEditorField.SERVER_PORT, "70000").toProfile())
    }
}
