package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import kotlinx.coroutines.flow.Flow
import org.junit.After
import org.junit.Before

internal abstract class AwgProfileRepositoryRoomFixture {
    protected lateinit var db: RipDpiDatabase
    protected lateinit var dao: AwgProfileDao
    protected lateinit var failingDao: FailingAwgProfileDao
    protected lateinit var credentialStore: FakeAwgCredentialStore
    protected lateinit var repository: AwgProfileRepository

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
        repository =
            AwgProfileRepository(
                dao,
                credentialStore,
                com.poyka.ripdpi.data.awg
                    .TestDirectAwgProfileMutationCoordinator(dao, credentialStore),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    protected fun sampleRequest(profileId: String = "") =
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
            obfuscation = AwgActivationObfuscation(jc = 4, jmax = 70, h1 = "1000000001", i1 = "<b 0xdeadbeef>"),
        )
}

/**
 * In-memory [AwgCredentialStore] double so the test exercises the REAL secret
 * split without an AndroidKeyStore dependency. Mirrors the fake-store doubles used
 * by the other repository round-trip tests.
 */
internal class FakeAwgCredentialStore : AwgCredentialStore {
    private val secrets = mutableMapOf<String, AwgSecrets>()
    var failNextSaveAfterWrite: Boolean = false

    /** Profile ids whose sealed value no longer decrypts. */
    val unreadable = mutableSetOf<String>()

    override suspend fun load(profileId: String): AwgSecrets? {
        if (profileId in unreadable) throw unreadableFixture()
        return secrets[profileId]
    }

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        unreadable -= profileId
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

internal class FailingAwgProfileDao(
    private val delegate: AwgProfileDao,
) : AwgProfileDao {
    var failNextUpsertAfterWrite = false

    override fun observeProfiles(): Flow<List<com.poyka.ripdpi.data.awg.AwgProfileEntity>> = delegate.observeProfiles()

    override suspend fun allProfiles(): List<com.poyka.ripdpi.data.awg.AwgProfileEntity> = delegate.allProfiles()

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

    override suspend fun deleteAll() {
        delegate.deleteAll()
    }
}
