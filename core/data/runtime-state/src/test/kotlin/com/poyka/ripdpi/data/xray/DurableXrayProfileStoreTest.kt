package com.poyka.ripdpi.data.xray

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline round-trip + no-plaintext-leak tests for the durable secret-bearing
 * Xray profile store. Uses in-memory fakes for both halves (no Android Context /
 * Keystore needed), and asserts that secrets land ONLY in the secret half while
 * the metadata half never contains a UUID or REALITY key in any encoding.
 */
class DurableXrayProfileStoreTest {
    private val metadataStore = FakeXrayProfileMetadataStore()
    private val secretStore = FakeXrayProfileSecretStore()
    private val store = DefaultDurableXrayProfileStore(metadataStore, secretStore)

    private val realityProfile =
        XrayProfile(
            name = "Tokyo",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = "edge.example.com",
                    serverPort = 8443,
                    uuid = "11111111-2222-3333-4444-555555555555",
                    flow = "xtls-rprx-vision",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = "PUBKEY_SECRET_abcdef",
                            serverName = "www.cloudflare.com",
                            shortId = "ab12",
                            fingerprint = "chrome",
                            privateKey = "PRIVKEY_SECRET_zzz",
                        ),
                ),
        )

    @Test
    fun `round-trips a reality profile through both halves`() =
        runTest {
            store.save("default", realityProfile)
            val loaded = store.load("default")
            assertEquals(realityProfile, loaded)
        }

    @Test
    fun `round-trips a tls xhttp profile`() =
        runTest {
            val tlsProfile =
                XrayProfile(
                    name = "Frankfurt",
                    outbound =
                        XrayProfile.Outbound(
                            serverAddress = "cdn.example.net",
                            serverPort = 443,
                            uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                            security = XrayProfile.Security.TLS,
                            network = XrayProfile.Network.XHTTP,
                            tls = XrayProfile.Tls(serverName = "cdn.example.net"),
                            xhttp = XrayProfile.Xhttp(path = "/dl", mode = "stream-up", host = "cdn.example.net"),
                        ),
                )
            store.save("p2", tlsProfile)
            assertEquals(tlsProfile, store.load("p2"))
        }

    @Test
    fun `metadata half never contains the uuid or reality keys`() =
        runTest {
            store.save("default", realityProfile)
            val metaBlob = metadataStore.rawBlob("default")
            assertFalse("uuid leaked into metadata", metaBlob.contains("11111111-2222-3333"))
            assertFalse("reality public key leaked into metadata", metaBlob.contains("PUBKEY_SECRET"))
            assertFalse("reality private key leaked into metadata", metaBlob.contains("PRIVKEY_SECRET"))
        }

    @Test
    fun `secret half carries exactly the secrets`() =
        runTest {
            store.save("default", realityProfile)
            val secret = secretStore.load("default")!!
            assertEquals("11111111-2222-3333-4444-555555555555", secret.uuid)
            assertEquals("PUBKEY_SECRET_abcdef", secret.realityPublicKey)
            assertEquals("PRIVKEY_SECRET_zzz", secret.realityPrivateKey)
        }

    @Test
    fun `load returns null when the secret half is missing`() =
        runTest {
            // Persist only metadata (simulating a kill between the two writes).
            metadataStore.save(XrayProfileMetadataRecord(profileId = "orphan", name = "x", serverAddress = "h"))
            assertNull(store.load("orphan"))
        }

    @Test
    fun `load returns null when no profile persisted`() =
        runTest {
            assertNull(store.load("missing"))
        }

    @Test
    fun `clear removes both halves`() =
        runTest {
            store.save("default", realityProfile)
            store.clear("default")
            assertNull(metadataStore.load("default"))
            assertNull(secretStore.load("default"))
        }

    @Test
    fun `listProfileIds only returns ids with both halves`() =
        runTest {
            store.save("complete", realityProfile)
            metadataStore.save(XrayProfileMetadataRecord(profileId = "metaonly", name = "x"))
            val ids = store.listProfileIds()
            assertTrue(ids.contains("complete"))
            assertFalse(ids.contains("metaonly"))
        }
}

private class FakeXrayProfileMetadataStore : XrayProfileMetadataStore {
    private val records = mutableMapOf<String, XrayProfileMetadataRecord>()

    /** The exact serialized form production would persist (plaintext JSON). */
    fun rawBlob(profileId: String): String =
        records[profileId]?.let {
            RipDpiJson.encodeToString(XrayProfileMetadataRecord.serializer(), it)
        } ?: ""

    override suspend fun load(profileId: String): XrayProfileMetadataRecord? = records[profileId]

    override suspend fun list(): List<XrayProfileMetadataRecord> = records.values.sortedBy { it.profileId }

    override suspend fun save(record: XrayProfileMetadataRecord) {
        records[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}

private class FakeXrayProfileSecretStore : XrayProfileSecretStore {
    private val records = mutableMapOf<String, XrayProfileSecretRecord>()

    override suspend fun load(profileId: String): XrayProfileSecretRecord? = records[profileId]

    override suspend fun save(record: XrayProfileSecretRecord) {
        records[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}
