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
    fun `interrupted replacement never joins new secrets with old metadata`() =
        runTest {
            store.save("default", realityProfile)
            val replacement =
                realityProfile.copy(
                    outbound = realityProfile.outbound.copy(uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                )
            metadataStore.failNextSave = true

            assertTrue(runCatching { store.save("default", replacement) }.isFailure)
            assertNull(store.load("default"))
            assertFalse(store.listProfileIds().contains("default"))

            store.save("default", replacement)
            assertEquals(replacement, store.load("default"))
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
    fun `load rejects matching halves stored under another profile key`() =
        runTest {
            val records = realityProfile.toXrayProfileRecordPair(profileId = "other", revision = "revision-a")
            metadataStore.saveUnder("default", records.metadata)
            secretStore.saveUnder("default", records.secret)
            assertNull(store.load("default"))
        }

    @Test
    fun `load returns null when stored secret profile id mismatches metadata`() =
        runTest {
            val records = realityProfile.toXrayProfileRecordPair(profileId = "default", revision = "revision-a")
            metadataStore.save(records.metadata)
            secretStore.saveUnder(
                key = "default",
                record = records.secret.copy(profileId = "other"),
            )

            assertNull(store.load("default"))
        }

    @Test
    fun `load returns null for unknown security instead of falling back to reality`() =
        runTest {
            val records = realityProfile.toXrayProfileRecordPair(profileId = "default", revision = "revision-a")
            metadataStore.save(records.metadata.copy(security = "unknown-security"))
            secretStore.save(records.secret)

            assertNull(store.load("default"))
        }

    @Test
    fun `load returns null for unknown network instead of falling back to tcp`() =
        runTest {
            val records = realityProfile.toXrayProfileRecordPair(profileId = "default", revision = "revision-a")
            metadataStore.save(records.metadata.copy(network = "unknown-network"))
            secretStore.save(records.secret)

            assertNull(store.load("default"))
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
    var failNextSave = false

    /** The exact serialized form production would persist (plaintext JSON). */
    fun rawBlob(profileId: String): String =
        records[profileId]?.let {
            RipDpiJson.encodeToString(XrayProfileMetadataRecord.serializer(), it)
        } ?: ""

    fun saveUnder(
        key: String,
        record: XrayProfileMetadataRecord,
    ) {
        records[key] = record
    }

    override suspend fun load(profileId: String): XrayProfileMetadataRecord? = records[profileId]

    override suspend fun list(): List<XrayProfileMetadataRecord> = records.values.sortedBy { it.profileId }

    override suspend fun save(record: XrayProfileMetadataRecord) {
        if (failNextSave) {
            failNextSave = false
            throw java.io.IOException("Metadata write interrupted")
        }
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

    fun saveUnder(
        key: String,
        record: XrayProfileSecretRecord,
    ) {
        records[key] = record
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}
