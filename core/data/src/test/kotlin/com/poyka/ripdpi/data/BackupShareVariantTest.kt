package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupVariant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [BackupVariant.SHARE] strips REDACTED and EXCLUDED fields
 * and preserves PUBLIC fields for every protocol bean.
 */
class BackupShareVariantTest {
    private fun shareExport(profiles: List<ProxyProfile>) =
        BackupExporter.export(
            variant = BackupVariant.SHARE,
            profiles = profiles,
            groups = emptyList(),
            rules = emptyList(),
            settings = emptyMap(),
            createdAtEpochMillis = 0L,
            appVersion = "1.0.0",
        )

    @Test
    fun `SHARE export of Vless strips uuid id and endpoint metadata`() {
        val profile =
            ProxyProfile.Vless(
                id = "v-1",
                displayName = "My VLESS",
                groupId = "g-1",
                server = "vless.example.com",
                serverPort = 443,
                uuid = "secret-uuid",
            )
        val doc = shareExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("displayName missing", "displayName" in obj)

        assertFalse("uuid must be stripped in SHARE", "uuid" in obj)
        assertFalse("server must be stripped in SHARE", "server" in obj)
        assertFalse("serverPort must be stripped in SHARE", "serverPort" in obj)
        assertFalse("id must be excluded in SHARE", "id" in obj)
    }

    @Test
    fun `SHARE export of Vless Reality strips endpoint and carrier metadata`() {
        val profile =
            ProxyProfile.VlessReality(
                id = "vr-1",
                displayName = "My VLESS Reality",
                groupId = "g-1",
                server = "reality.example.com",
                serverPort = 443,
                uuid = "secret-uuid",
                realityPublicKey = "public-key-fixture",
                realityShortId = "short-id-fixture",
                serverName = "front.example.com",
                flow = "xtls-rprx-vision",
                fingerprint = "chrome",
                xhttpPath = "/carrier/path",
                xhttpHost = "cdn.example.com",
                xhttpMode = "stream-one",
            )
        val doc = shareExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("displayName missing", "displayName" in obj)
        assertTrue("flow missing", "flow" in obj)
        assertTrue("fingerprint missing", "fingerprint" in obj)
        assertTrue("xhttpMode missing", "xhttpMode" in obj)
        assertTrue("realityPublicKey missing", "realityPublicKey" in obj)
        assertTrue("realityShortId missing", "realityShortId" in obj)

        assertFalse("uuid must be stripped in SHARE", "uuid" in obj)
        assertFalse("server must be stripped in SHARE", "server" in obj)
        assertFalse("serverPort must be stripped in SHARE", "serverPort" in obj)
        assertFalse("serverName must be stripped in SHARE", "serverName" in obj)
        assertFalse("xhttpHost must be stripped in SHARE", "xhttpHost" in obj)
        assertFalse("xhttpPath must be stripped in SHARE", "xhttpPath" in obj)
        assertFalse("id must be excluded in SHARE", "id" in obj)
    }

    @Test
    fun `SHARE export of Shadowsocks strips password and id, keeps server method and serverPort`() {
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
        val doc = shareExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("server missing", "server" in obj)
        assertTrue("serverPort missing", "serverPort" in obj)
        assertTrue("method missing", "method" in obj)

        assertFalse("password must be stripped in SHARE", "password" in obj)
        assertFalse("id must be excluded in SHARE", "id" in obj)
    }

    @Test
    fun `SHARE export of Hysteria2 strips password and id, keeps server and serverPort`() {
        val profile =
            ProxyProfile.Hysteria2(
                id = "hy2-1",
                displayName = "My Hy2",
                groupId = "g-1",
                server = "hy2.example.com",
                serverPort = 8443,
                password = "fixture-password-2",
            )
        val doc = shareExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("server missing", "server" in obj)
        assertTrue("serverPort missing", "serverPort" in obj)

        assertFalse("password must be stripped in SHARE", "password" in obj)
        assertFalse("id must be excluded in SHARE", "id" in obj)
    }

    @Test
    fun `SHARE export of Trojan strips password and id, keeps server and serverPort`() {
        val profile =
            ProxyProfile.Trojan(
                id = "tr-1",
                displayName = "My Trojan",
                groupId = "g-1",
                server = "trojan.example.com",
                serverPort = 443,
                password = "fixture-password-3",
            )
        val doc = shareExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("server missing", "server" in obj)
        assertTrue("serverPort missing", "serverPort" in obj)

        assertFalse("password must be stripped in SHARE", "password" in obj)
        assertFalse("id must be excluded in SHARE", "id" in obj)
    }

    @Test
    fun `SHARE export of RawConfig strips config and id, keeps displayName`() {
        val profile =
            ProxyProfile.RawConfig(
                id = "rc-1",
                displayName = "My Raw",
                groupId = "g-1",
                config = "{\"secret\": true}",
            )
        val doc = shareExport(listOf(profile))
        val obj = doc.profiles.single()

        assertTrue("displayName missing", "displayName" in obj)

        assertFalse("config must be stripped in SHARE", "config" in obj)
        assertFalse("id must be excluded in SHARE", "id" in obj)
    }

    @Test
    fun `SHARE export containsCredentials is false`() {
        val doc = shareExport(emptyList())
        assertFalse(doc.containsCredentials)
    }
}
