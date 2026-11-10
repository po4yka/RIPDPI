package com.poyka.ripdpi.ui.screens.mieru

import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MieruProfileEditorStateTest {
    private fun filled(
        username: String = "alice",
        server: String = "mieru.example.com",
    ): MieruProfileEditorState =
        MieruProfileEditorState
            .initial()
            .updateField(MieruEditorField.SERVER, server)
            .updateField(MieruEditorField.SERVER_PORT, "8443")
            .updateField(MieruEditorField.USERNAME, username)
            .updateField(MieruEditorField.PASSWORD, "pw-fixture")

    @Test
    fun `user name is limited to 64 UTF-8 bytes`() {
        assertNotNull(filled(username = "a".repeat(64)).toProfile())

        val tooLong = filled(username = "a".repeat(65))
        assertTrue(tooLong.hasFieldError(MieruEditorField.USERNAME))
        assertNull(tooLong.toProfile())
        assertNull(filled(username = "é".repeat(33)).toProfile())
    }

    @Test
    fun `blank server is not complete`() {
        assertFalse(filled(server = "   ").isComplete)
        assertNull(filled(server = "   ").toProfile())
    }

    @Test
    fun `udp profile is not saved`() {
        val udp = filled().copy(protocol = RelayMieruProtocolUdp)

        assertNull(udp.toProfile())
    }
}
