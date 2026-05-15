package com.poyka.ripdpi.data.outbound

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboundProxyProfilesTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── Redacted wrapper ──────────────────────────────────────────────────────

    @Test
    fun `redacted toString masks the value`() {
        val secret = Redacted("super-secret-password")
        assertEquals("<redacted>", secret.toString())
    }

    @Test
    fun `redacted value is accessible for protocol use`() {
        val secret = Redacted("hunter2")
        assertEquals("hunter2", secret.value)
    }

    @Test
    fun `redacted toString does not contain the raw value`() {
        val secret = Redacted("hunter2")
        assertNotEquals("hunter2", secret.toString())
    }

    // ── Socks5OutboundRuntimeState ────────────────────────────────────────────

    @Test
    fun `socks5 profile with credentials round-trips through json`() {
        val profile =
            Socks5OutboundRuntimeState(
                server = "proxy.example.com",
                port = 1080,
                username = Redacted("alice"),
                password = Redacted("s3cr3t"),
            )

        val encoded = json.encodeToString(Socks5OutboundRuntimeState.serializer(), profile)
        val decoded = json.decodeFromString(Socks5OutboundRuntimeState.serializer(), encoded)

        assertEquals(profile, decoded)
        assertEquals("proxy.example.com", decoded.server)
        assertEquals(1080, decoded.port)
        assertEquals("alice", decoded.username?.value)
        assertEquals("s3cr3t", decoded.password?.value)
    }

    @Test
    fun `socks5 profile without credentials round-trips through json`() {
        val profile =
            Socks5OutboundRuntimeState(
                server = "open-proxy.example.com",
                port = 1080,
            )

        val encoded = json.encodeToString(Socks5OutboundRuntimeState.serializer(), profile)
        val decoded = json.decodeFromString(Socks5OutboundRuntimeState.serializer(), encoded)

        assertEquals(profile, decoded)
        assertNull(decoded.username)
        assertNull(decoded.password)
    }

    @Test
    fun `socks5 profile password redacted in toString`() {
        val profile =
            Socks5OutboundRuntimeState(
                server = "proxy.example.com",
                port = 1080,
                username = Redacted("alice"),
                password = Redacted("s3cr3t"),
            )
        val str = profile.toString()
        assert(!str.contains("s3cr3t")) {
            "toString must not contain the raw password, got: $str"
        }
        assert(!str.contains("alice")) {
            "toString must not contain the raw username, got: $str"
        }
    }

    @Test
    fun `socks5 profile defaults to null credentials`() {
        val profile = Socks5OutboundRuntimeState(server = "p.example.com", port = 1080)
        assertNull(profile.username)
        assertNull(profile.password)
    }

    // ── HttpProxyOutboundRuntimeState ─────────────────────────────────────────

    @Test
    fun `http proxy profile with tls and sni round-trips through json`() {
        val profile =
            HttpProxyOutboundRuntimeState(
                server = "https-proxy.example.com",
                port = 8080,
                username = Redacted("bob"),
                password = Redacted("p4ss"),
                tls = true,
                sni = "override.example.com",
            )

        val encoded = json.encodeToString(HttpProxyOutboundRuntimeState.serializer(), profile)
        val decoded = json.decodeFromString(HttpProxyOutboundRuntimeState.serializer(), encoded)

        assertEquals(profile, decoded)
        assertEquals(true, decoded.tls)
        assertEquals("override.example.com", decoded.sni)
        assertEquals("bob", decoded.username?.value)
        assertEquals("p4ss", decoded.password?.value)
    }

    @Test
    fun `http proxy profile without tls defaults to false and null sni`() {
        val profile =
            HttpProxyOutboundRuntimeState(
                server = "proxy.example.com",
                port = 3128,
            )

        val encoded = json.encodeToString(HttpProxyOutboundRuntimeState.serializer(), profile)
        val decoded = json.decodeFromString(HttpProxyOutboundRuntimeState.serializer(), encoded)

        assertEquals(false, decoded.tls)
        assertNull(decoded.sni)
        assertNull(decoded.username)
        assertNull(decoded.password)
    }

    @Test
    fun `http proxy profile password redacted in toString`() {
        val profile =
            HttpProxyOutboundRuntimeState(
                server = "proxy.example.com",
                port = 3128,
                username = Redacted("carol"),
                password = Redacted("mypassword"),
            )
        val str = profile.toString()
        assert(!str.contains("mypassword")) {
            "toString must not contain the raw password, got: $str"
        }
        assert(!str.contains("carol")) {
            "toString must not contain the raw username, got: $str"
        }
    }

    @Test
    fun `http proxy profile tls true without sni uses null sni`() {
        val profile =
            HttpProxyOutboundRuntimeState(
                server = "proxy.example.com",
                port = 443,
                tls = true,
            )
        assertEquals(true, profile.tls)
        assertNull(profile.sni)
    }
}
