package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips the standalone-AmneziaWG [AwgProfileRepository] through a real
 * Robolectric Room database. Mirrors `RuleEntityRoomTest`.
 *
 * The teeth of the slice: a saved profile re-loads under the SAME stable id, the
 * minted id is opaque (never endpoint-derived), and a re-save under that id
 * updates the row in place instead of minting a duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AwgProfileRepositoryRoomTest {
    private lateinit var db: RipDpiDatabase
    private lateinit var dao: AwgProfileDao
    private lateinit var failingDao: FailingAwgProfileDao
    private lateinit var credentialStore: FakeAwgCredentialStore
    private lateinit var repository: AwgProfileRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        failingDao = FailingAwgProfileDao(db.awgProfileDao())
        dao = failingDao
        credentialStore = FakeAwgCredentialStore()
        repository = AwgProfileRepository(dao, credentialStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleRequest(profileId: String = "") =
        AwgActivationRequest(
            profileId = profileId,
            privateKey = "privkey==",
            peerPublicKey = "peerpub==",
            presharedKey = "psk==",
            endpointHost = "vpn.example.com",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            mtu = 1280,
            persistentKeepalive = 25,
            obfuscation = AwgActivationObfuscation(jc = 4, jmax = 70, h1 = 1_000_000_001L, i1 = "deadbeef"),
        )

    @Test
    fun `save mints an opaque profile id and load round-trips every field`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())

            // The id is opaque and NOT derived from the endpoint (privacy invariant).
            assertTrue("id must use the opaque awg- prefix", id.startsWith("awg-"))
            assertFalse("id must not leak the endpoint host", id.contains("vpn.example.com"))
            assertFalse("id must not leak the endpoint port", id.contains("51820"))

            val loaded = repository.load(id)!!
            assertEquals("home", loaded.name)
            assertEquals(id, loaded.id)
            // The loaded request is stamped with the stable row id as its profileId.
            assertEquals(id, loaded.request.profileId)
            assertEquals("vpn.example.com", loaded.request.endpointHost)
            assertEquals(51820, loaded.request.endpointPort)
            assertEquals("privkey==", loaded.request.privateKey)
            assertEquals("psk==", loaded.request.presharedKey)
            assertEquals("10.8.0.2/32", loaded.request.interfaceAddressV4)
            assertEquals(1280, loaded.request.mtu)
            assertEquals(25, loaded.request.persistentKeepalive)
            assertEquals(4, loaded.request.obfuscation.jc)
            assertEquals(70, loaded.request.obfuscation.jmax)
            assertEquals(1_000_000_001L, loaded.request.obfuscation.h1)
            assertEquals("deadbeef", loaded.request.obfuscation.i1)
        }

    @Test
    fun `re-saving under the existing id reuses the row and keeps a stable id`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())

            val reusedId =
                repository.save(
                    name = "home renamed",
                    request = sampleRequest().copy(endpointPort = 4443),
                    existingId = id,
                )

            assertEquals("re-save must reuse the same stable id", id, reusedId)
            val all = repository.observeProfiles().first()
            assertEquals("re-save must update in place, not duplicate", 1, all.size)
            assertEquals("home renamed", all[0].name)
            assertEquals(4443, all[0].request.endpointPort)
            // The re-loaded request still carries the stable id as profileId.
            assertEquals(id, all[0].request.profileId)
        }

    @Test
    fun `failed credential update restores the previous profile snapshot`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())
            credentialStore.failNextSaveAfterWrite = true

            val error =
                runCatching {
                    repository.save(
                        name = "changed",
                        request = sampleRequest().copy(privateKey = "changed-private-key", endpointPort = 4443),
                        existingId = id,
                    )
                }.exceptionOrNull()

            assertNotNull(error)
            val restored = repository.load(id)!!
            assertEquals("home", restored.name)
            assertEquals(51820, restored.request.endpointPort)
            assertEquals("privkey==", restored.request.privateKey)
        }

    @Test
    fun `failed Room update restores the previous profile snapshot`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())
            failingDao.failNextUpsertAfterWrite = true

            val error =
                runCatching {
                    repository.save(
                        name = "changed",
                        request = sampleRequest().copy(privateKey = "changed-private-key", endpointPort = 4443),
                        existingId = id,
                    )
                }.exceptionOrNull()

            assertNotNull(error)
            val restored = repository.load(id)!!
            assertEquals("home", restored.name)
            assertEquals(51820, restored.request.endpointPort)
            assertEquals("privkey==", restored.request.privateKey)
        }

    @Test
    fun `the persisted blob never pins a specific activation id`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest(profileId = "awg-should-be-blanked"))

            val row = dao.getProfile(id)!!
            // The stored JSON blanks profileId; the row id is the single source of truth.
            assertFalse(
                "the serialized blob must blank profileId",
                row.requestJson.contains("awg-should-be-blanked"),
            )
            assertEquals(id, repository.load(id)!!.request.profileId)
        }

    @Test
    fun `the persisted blob holds no private or preshared key material and load re-injects it`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())

            // The Room blob must contain NO secret material -- it lives encrypted elsewhere.
            val row = dao.getProfile(id)!!
            assertFalse("the blob must not persist the private key", row.requestJson.contains("privkey=="))
            assertFalse("the blob must not persist the preshared key", row.requestJson.contains("psk=="))

            // The secrets are sealed in the credential store, keyed by the stable id.
            assertEquals("privkey==", credentialStore.load(id)?.privateKey)
            assertEquals("psk==", credentialStore.load(id)?.presharedKey)

            // load() re-injects the secrets so the round-tripped request equals the original.
            val loaded = repository.load(id)!!
            assertEquals(sampleRequest().copy(profileId = id), loaded.request)
        }

    @Test
    fun `delete removes the encrypted secrets alongside the row`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())
            assertNotNull(credentialStore.load(id))

            repository.delete(id)

            assertNull("delete must also clear the sealed secrets", credentialStore.load(id))
        }

    @Test
    fun `deleteAll removes every persisted profile row`() =
        runTest {
            repository.save(name = "home", request = sampleRequest())
            repository.save(name = "work", request = sampleRequest().copy(endpointPort = 443))

            dao.deleteAll()

            assertTrue(repository.observeProfiles().first().isEmpty())
        }

    @Test
    fun `a stored blob with an unknown extra key decodes without throwing`() =
        runTest {
            val id = repository.save(name = "home", request = sampleRequest())

            // Simulate a row written by a FUTURE app version carrying an additive field.
            val row = dao.getProfile(id)!!
            val forwardCompatJson =
                row.requestJson.replaceFirst("{", """{"unknownFutureField":"surprise",""")
            dao.upsertProfile(row.copy(requestJson = forwardCompatJson))

            // Decode must tolerate the unknown key instead of throwing into a silent Failed.
            val loaded = repository.load(id)
            assertNotNull("forward-compatible decode must not throw", loaded)
            assertEquals("vpn.example.com", loaded!!.request.endpointHost)
            // Secrets are still re-injected from the credential store on the rehydrated row.
            assertEquals("privkey==", loaded.request.privateKey)
        }

    @Test
    fun `observeProfiles emits on save and delete`() =
        runTest {
            repository.observeProfiles().test {
                assertEquals(emptyList<String>(), awaitItem().map { it.name })

                val id = repository.save(name = "first", request = sampleRequest())
                assertEquals(listOf("first"), awaitItem().map { it.name })

                repository.delete(id)
                assertEquals(emptyList<String>(), awaitItem().map { it.name })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `delete of an unknown id is a no-op`() =
        runTest {
            repository.delete("awg-does-not-exist")
            assertNull(repository.load("awg-does-not-exist"))
        }

    @Test
    fun `save rejects non-zero S3 or S4 before persisting secrets or a row`() =
        runTest {
            val unsafe = sampleRequest().copy(obfuscation = sampleRequest().obfuscation.copy(s4 = 1))

            val error = runCatching { repository.save(name = "unsafe", request = unsafe) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("amneziawg-go#110"))
            assertTrue(repository.observeProfiles().first().isEmpty())
            assertTrue(credentialStore.isEmpty())
        }
}

/**
 * In-memory [AwgCredentialStore] double so the test exercises the REAL secret
 * split without an AndroidKeyStore dependency. Mirrors the fake-store doubles used
 * by the other repository round-trip tests.
 */
private class FakeAwgCredentialStore : AwgCredentialStore {
    private val secrets = mutableMapOf<String, AwgSecrets>()
    var failNextSaveAfterWrite: Boolean = false

    override suspend fun load(profileId: String): AwgSecrets? = secrets[profileId]

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        this.secrets[profileId] = secrets
        if (failNextSaveAfterWrite) {
            failNextSaveAfterWrite = false
            error("credential save failed after write")
        }
    }

    override suspend fun clear(profileId: String) {
        secrets.remove(profileId)
    }

    fun isEmpty(): Boolean = secrets.isEmpty()
}

private class FailingAwgProfileDao(
    private val delegate: AwgProfileDao,
) : AwgProfileDao {
    var failNextUpsertAfterWrite = false

    override fun observeProfiles(): Flow<List<com.poyka.ripdpi.data.awg.AwgProfileEntity>> = delegate.observeProfiles()

    override suspend fun getProfile(id: String): com.poyka.ripdpi.data.awg.AwgProfileEntity? = delegate.getProfile(id)

    override suspend fun upsertProfile(profile: com.poyka.ripdpi.data.awg.AwgProfileEntity) {
        delegate.upsertProfile(profile)
        if (failNextUpsertAfterWrite) {
            failNextUpsertAfterWrite = false
            error("Room upsert failed after write")
        }
    }

    override suspend fun deleteProfile(profile: com.poyka.ripdpi.data.awg.AwgProfileEntity) {
        delegate.deleteProfile(profile)
    }
}
