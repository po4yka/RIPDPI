package com.poyka.ripdpi.data.xray

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.persistence.KeystoreProfileRecordCipher
import com.poyka.ripdpi.data.persistence.LegacyProfileImporter
import com.poyka.ripdpi.data.persistence.ProfileDatabase
import com.poyka.ripdpi.data.persistence.ProfileRecordCipher
import com.poyka.ripdpi.data.persistence.RoomDurableXrayProfileStore
import com.poyka.ripdpi.data.persistence.RoomXrayProfileMetadataStore
import com.poyka.ripdpi.data.persistence.RoomXrayProfileSecretStore
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

/** Runs the production Xray stores against real Room and AES-GCM records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DurableXrayProfileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java).build()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = KeystoreProfileRecordCipher({ key }, { key })
    private var failNextNamespace: String? = null
    private val faultCipher =
        object : ProfileRecordCipher by cipher {
            override fun seal(
                namespace: String,
                id: String,
                plaintext: ByteArray,
            ): ByteArray {
                if (namespace == failNextNamespace) {
                    failNextNamespace = null
                    error("Injected encrypted row write failure")
                }
                return cipher.seal(namespace, id, plaintext)
            }
        }
    private val profileDatabase =
        ProfileDatabase(database, faultCipher, LegacyProfileImporter(context, testPauseAuthority()))
    private val metadataStore = RoomXrayProfileMetadataStore(profileDatabase)
    private val secretStore = RoomXrayProfileSecretStore(profileDatabase)
    private val store = RoomDurableXrayProfileStore(profileDatabase)

    @After
    fun closeDatabase() = database.close()

    private suspend fun saveMetadataUnder(
        key: String,
        record: XrayProfileMetadataRecord,
    ) {
        profileDatabase.write { rows ->
            rows.records.write(
                "xray_metadata",
                key,
                RipDpiJson.encodeToString(XrayProfileMetadataRecord.serializer(), record).encodeToByteArray(),
            )
        }
    }

    private suspend fun saveSecretUnder(
        key: String,
        record: XrayProfileSecretRecord,
    ) {
        profileDatabase.write { rows ->
            rows.records.write(
                "xray_secrets",
                key,
                RipDpiJson.encodeToString(XrayProfileSecretRecord.serializer(), record).encodeToByteArray(),
            )
        }
    }

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
            failNextNamespace = "xray_secrets"

            assertTrue(runCatching { store.save("default", replacement) }.isFailure)
            assertEquals(realityProfile, store.load("default"))
            assertTrue(store.listProfileIds().contains("default"))

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
            val metaBlob =
                RipDpiJson.encodeToString(
                    XrayProfileMetadataRecord.serializer(),
                    checkNotNull(metadataStore.load("default")),
                )
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
            saveMetadataUnder("default", records.metadata)
            saveSecretUnder("default", records.secret)
            assertNull(store.load("default"))
        }

    @Test
    fun `load returns null when stored secret profile id mismatches metadata`() =
        runTest {
            val records = realityProfile.toXrayProfileRecordPair(profileId = "default", revision = "revision-a")
            metadataStore.save(records.metadata)
            saveSecretUnder(
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
