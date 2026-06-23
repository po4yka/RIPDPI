package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [BackupVariant.FULL] keeps all fields and sets containsCredentials = true.
 */
class BackupFullVariantTest {
    private fun fullExport(profiles: List<ProxyProfile>) =
        BackupExporter.export(
            variant = BackupVariant.FULL,
            profiles = profiles,
            groups = emptyList(),
            rules = emptyList(),
            settings = emptyMap(),
            createdAtEpochMillis = 0L,
            appVersion = "1.0.0",
        )

    @Test
    fun `FULL export sets containsCredentials to true`() {
        val doc = fullExport(emptyList())
        assertTrue(doc.containsCredentials)
    }

    @Test
    fun `FULL export of Vless keeps all fields including uuid and id`() {
        val profile =
            ProxyProfile.Vless(
                id = "v-1",
                displayName = "My VLESS",
                groupId = "g-1",
                server = "vless.example.com",
                serverPort = 443,
                uuid = "secret-uuid",
            )
        val doc = fullExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("server missing", "server" in obj)
        assertTrue("serverPort missing", "serverPort" in obj)
        assertTrue("uuid missing in FULL", "uuid" in obj)
        assertTrue("id missing in FULL", "id" in obj)
        assertEquals("secret-uuid", obj.getValue("uuid").toString().trim('"'))
    }

    @Test
    fun `FULL export of Shadowsocks keeps password`() {
        val profile =
            ProxyProfile.Shadowsocks(
                id = "ss-1",
                displayName = "My SS",
                groupId = "g-1",
                server = "ss.example.com",
                serverPort = 8388,
                method = "aes-256-gcm",
                password = "fixture-password-1",
            )
        val doc = fullExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("password missing in FULL", "password" in obj)
        assertEquals("fixture-password-1", obj.getValue("password").toString().trim('"'))
    }

    @Test
    fun `FULL export of Hysteria2 keeps password`() {
        val profile =
            ProxyProfile.Hysteria2(
                id = "hy2-1",
                displayName = "My Hy2",
                groupId = "g-1",
                server = "hy2.example.com",
                serverPort = 8443,
                password = "fixture-password-2",
            )
        val doc = fullExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("password missing in FULL", "password" in obj)
    }

    @Test
    fun `FULL export of Ssh keeps secret key material`() {
        val profile =
            ProxyProfile.Ssh(
                id = "ssh-1",
                displayName = "My SSH",
                groupId = "g-1",
                server = "ssh.example.com",
                serverPort = 22,
                username = "operator",
                authType = "private_key",
                password = "fixture-password-4",
                privateKey = "fixture-private-key",
                privateKeyPassphrase = "fixture-passphrase",
            )
        val doc = fullExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("password missing in FULL", "password" in obj)
        assertTrue("privateKey missing in FULL", "privateKey" in obj)
        assertTrue("privateKeyPassphrase missing in FULL", "privateKeyPassphrase" in obj)
        assertEquals("fixture-private-key", obj.getValue("privateKey").toString().trim('"'))
    }

    @Test
    fun `FULL export preserves multiple profiles`() {
        val profiles =
            listOf(
                ProxyProfile.Vless(
                    id = "v-1",
                    displayName = "VLESS",
                    groupId = "g-1",
                    server = "v.example.com",
                    serverPort = 443,
                    uuid = "uuid-1",
                ),
                ProxyProfile.Shadowsocks(
                    id = "ss-1",
                    displayName = "SS",
                    groupId = "g-1",
                    server = "ss.example.com",
                    serverPort = 8388,
                    method = "chacha20-ietf-poly1305",
                    password = "pw",
                ),
            )
        val doc = fullExport(profiles)
        assertEquals(2, doc.profiles.size)
        assertTrue(doc.containsCredentials)
    }
}
