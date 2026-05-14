package com.poyka.ripdpi.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RelayProfileStoreMigrationTest {
    @Test
    fun `legacy xhttp record with empty reality public key migrates to plain tls`() {
        val legacy =
            RelayProfileRecord(
                id = "p1",
                kind = RelayKindVlessReality,
                server = "edge.example.com",
                serverName = "edge.example.com",
                realityPublicKey = "",
                realityShortId = "",
                vlessTransport = RelayVlessTransportXhttp,
                xhttpPath = "/xhttp",
                xhttpHost = "origin.example.com",
                securityLayer = RelaySecurityLayerReality,
            )

        val result = migrateRelayProfileRecord(legacy)

        assertTrue(result.changed)
        assertEquals(RelaySecurityLayerTls, result.record.securityLayer)
        assertEquals(RelayKindVless, result.record.kind)
        assertEquals(RelayVlessTransportXhttp, result.record.vlessTransport)
        assertEquals("/xhttp", result.record.xhttpPath)
        assertEquals("origin.example.com", result.record.xhttpHost)
    }

    @Test
    fun `legacy reality tcp record is left untouched`() {
        val legacy =
            RelayProfileRecord(
                id = "p0",
                kind = RelayKindVlessReality,
                server = "relay.example.com",
                serverName = "relay.example.com",
                realityPublicKey = "public-key",
                realityShortId = "short-id",
                vlessTransport = RelayVlessTransportRealityTcp,
                securityLayer = RelaySecurityLayerReality,
            )

        val result = migrateRelayProfileRecord(legacy)

        assertTrue(!result.changed)
        assertSame(legacy, result.record)
        assertEquals(RelaySecurityLayerReality, result.record.securityLayer)
    }

    @Test
    fun `reality xhttp record with a public key is left untouched`() {
        // P0 reality + xhttp: has a Reality public key, so it must stay reality.
        val legacy =
            RelayProfileRecord(
                id = "p0-xhttp",
                kind = RelayKindVlessReality,
                server = "relay.example.com",
                serverName = "relay.example.com",
                realityPublicKey = "public-key",
                realityShortId = "short-id",
                vlessTransport = RelayVlessTransportXhttp,
                xhttpPath = "/xhttp",
                securityLayer = RelaySecurityLayerReality,
            )

        val result = migrateRelayProfileRecord(legacy)

        assertTrue(!result.changed)
        assertEquals(RelaySecurityLayerReality, result.record.securityLayer)
        assertEquals(RelayKindVlessReality, result.record.kind)
    }

    @Test
    fun `migration is idempotent`() {
        val legacy =
            RelayProfileRecord(
                id = "p1",
                kind = RelayKindVlessReality,
                realityPublicKey = "",
                vlessTransport = RelayVlessTransportXhttp,
                xhttpPath = "/xhttp",
                securityLayer = RelaySecurityLayerReality,
            )

        val once = migrateRelayProfileRecord(legacy)
        assertTrue(once.changed)

        val twice = migrateRelayProfileRecord(once.record)
        assertTrue(!twice.changed)
        assertSame(once.record, twice.record)
        assertEquals(once.record, twice.record)
    }

    @Test
    fun `non vless kinds are never migrated`() {
        val cloudflare =
            RelayProfileRecord(
                id = "cf",
                kind = RelayKindCloudflareTunnel,
                realityPublicKey = "",
                vlessTransport = RelayVlessTransportXhttp,
            )

        val result = migrateRelayProfileRecord(cloudflare)

        assertTrue(!result.changed)
        assertSame(cloudflare, result.record)
    }

    @Test
    fun `profile store load rewrites a legacy on-disk record to the new shape`() =
        runTest {
            val store = SharedPreferencesRelayProfileStore(ApplicationProvider.getApplicationContext())
            // seed a legacy record directly (as if written by an older app version)
            store.save(
                RelayProfileRecord(
                    id = "p1",
                    kind = RelayKindVlessReality,
                    server = "edge.example.com",
                    serverName = "edge.example.com",
                    realityPublicKey = "",
                    vlessTransport = RelayVlessTransportXhttp,
                    xhttpPath = "/xhttp",
                    xhttpHost = "origin.example.com",
                    securityLayer = RelaySecurityLayerReality,
                ),
            )

            val loaded = store.load("p1")

            assertEquals(RelaySecurityLayerTls, loaded?.securityLayer)
            assertEquals(RelayKindVless, loaded?.kind)
            assertEquals(RelayVlessTransportXhttp, loaded?.vlessTransport)
            assertEquals("/xhttp", loaded?.xhttpPath)
            assertEquals("origin.example.com", loaded?.xhttpHost)

            // one-shot: the rewritten shape is persisted, so a re-load is stable
            val reloaded = store.load("p1")
            assertEquals(loaded, reloaded)
        }

    @Test
    fun `profile store load leaves a reality record untouched and returns null for missing`() =
        runTest {
            val store = SharedPreferencesRelayProfileStore(ApplicationProvider.getApplicationContext())
            store.save(
                RelayProfileRecord(
                    id = "p0",
                    kind = RelayKindVlessReality,
                    server = "relay.example.com",
                    serverName = "relay.example.com",
                    realityPublicKey = "public-key",
                    realityShortId = "short-id",
                    vlessTransport = RelayVlessTransportRealityTcp,
                    securityLayer = RelaySecurityLayerReality,
                ),
            )

            val loaded = store.load("p0")
            assertEquals(RelayKindVlessReality, loaded?.kind)
            assertEquals(RelaySecurityLayerReality, loaded?.securityLayer)
            assertNull(store.load("missing"))
        }
}
