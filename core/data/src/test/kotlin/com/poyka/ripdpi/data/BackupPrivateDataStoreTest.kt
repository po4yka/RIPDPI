package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.AwgBackupProfile
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.backup.DefaultBackupPrivateDataStore
import com.poyka.ripdpi.data.backup.consistentBackupSnapshot
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProviderSelectionStore
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupPrivateDataStoreTest {
    private lateinit var database: RipDpiDatabase
    private lateinit var warpEndpoints: SharedPreferencesWarpEndpointStore
    private lateinit var store: DefaultBackupPrivateDataStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RipDpiDatabase::class.java).allowMainThreadQueries().build()
        warpEndpoints = SharedPreferencesWarpEndpointStore(context)
        val relayProfiles = SharedPreferencesRelayProfileStore(context)
        val relayCredentials = BackupRelayCredentialStore()
        val warpProfiles = SharedPreferencesWarpProfileStore(context)
        val warpCredentials = BackupWarpCredentialStore()
        val awgCredentials = BackupAwgCredentialStore()
        val xrayMetadata = SharedPreferencesXrayProfileMetadataStore(context)
        val xraySecrets = BackupXraySecretStore()
        val xraySelection = SharedPreferencesXrayProviderSelectionStore(context)
        val profileMutations =
            ProfileMutationRecoveryCoordinator(
                stores =
                    ProfileMutationStores(
                        settings = BackupSettingsRepository(),
                        relayProfiles = relayProfiles,
                        relayCredentials = relayCredentials,
                        warpProfiles = warpProfiles,
                        warpCredentials = warpCredentials,
                        warpEndpoints = warpEndpoints,
                        xrayMetadata = xrayMetadata,
                        xraySecrets = xraySecrets,
                        xraySelection = xraySelection,
                        bootSession = BackupBootSessionStateStore(),
                    ),
                awgProfiles = database.awgProfileDao(),
                awgCredentials = awgCredentials,
                journal = BackupProfileMutationJournal(),
            )
        store =
            DefaultBackupPrivateDataStore(
                relayProfiles = relayProfiles,
                relayCredentials = relayCredentials,
                warpProfiles = warpProfiles,
                warpCredentials = warpCredentials,
                awgProfiles = database.awgProfileDao(),
                awgCredentials = awgCredentials,
                xrayMetadata = xrayMetadata,
                xraySecrets = xraySecrets,
                xraySelection = xraySelection,
                profileMutations = profileMutations,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `replaceAll round-trips every private profile family and clears endpoint cache`() =
        runTest {
            warpEndpoints.save(
                WarpEndpointCacheEntry(
                    profileId = "old-warp",
                    networkScopeKey = "old-network",
                    port = 2408,
                ),
            )
            val expected =
                BackupPrivateDataV1(
                    relayProfiles = listOf(RelayProfileRecord(id = "relay-1")),
                    relayCredentials = listOf(RelayCredentialRecord(profileId = "relay-1", vlessUuid = "uuid")),
                    warpProfiles = listOf(WarpProfile(id = "warp-1")),
                    warpCredentials =
                        listOf(WarpCredentials(profileId = "warp-1", deviceId = "device", accessToken = "token")),
                    warpActiveProfileId = "warp-1",
                    awgProfiles =
                        listOf(
                            AwgBackupProfile(
                                id = "awg-1",
                                name = "AWG",
                                requestJson = "{}",
                                updatedAt = 7L,
                                secrets = AwgSecrets(privateKey = "private", presharedKey = "psk"),
                            ),
                        ),
                    xrayMetadata =
                        listOf(
                            XrayProfileMetadataRecord(profileId = "xray-1", revision = "fixture", name = "Xray"),
                        ),
                    xraySecrets =
                        listOf(
                            XrayProfileSecretRecord(profileId = "xray-1", revision = "fixture", uuid = "xray-uuid"),
                        ),
                )

            store.replaceAll(expected)

            assertEquals(expected, store.snapshot())
            assertTrue(warpEndpoints.loadAll("old-warp").isEmpty())
        }

    @Test
    fun `snapshot rejects an unknown selected provider`() =
        runTest {
            val result =
                runCatching {
                    consistentBackupSnapshot {
                        BackupPrivateDataV1(xraySelection = XrayProviderSelectionRecord(providerKind = "unknown"))
                    }
                }
            assertTrue(result.isFailure)
        }

    @Test
    fun `snapshot waits for matching Xray profile revisions`() =
        runTest {
            var reads = 0
            val snapshot =
                consistentBackupSnapshot {
                    reads += 1
                    BackupPrivateDataV1(
                        xrayMetadata = listOf(XrayProfileMetadataRecord(profileId = "xray-1", revision = "old")),
                        xraySecrets =
                            listOf(
                                XrayProfileSecretRecord(
                                    profileId = "xray-1",
                                    revision =
                                        if (reads ==
                                            1
                                        ) {
                                            "new"
                                        } else {
                                            "old"
                                        },
                                ),
                            ),
                    )
                }
            assertEquals(2, reads)
            assertEquals(snapshot.xrayMetadata.single().revision, snapshot.xraySecrets.single().revision)
        }

    @Test
    fun `snapshot retries rather than exporting active id without profile`() =
        runTest {
            var reads = 0

            val snapshot =
                consistentBackupSnapshot {
                    reads += 1
                    if (reads == 1) {
                        BackupPrivateDataV1(warpActiveProfileId = "concurrent-profile")
                    } else {
                        BackupPrivateDataV1(
                            warpProfiles = listOf(WarpProfile(id = "concurrent-profile")),
                            warpActiveProfileId = "concurrent-profile",
                        )
                    }
                }

            assertEquals(2, reads)
            assertEquals("concurrent-profile", snapshot.warpProfiles.single().id)
            assertEquals("concurrent-profile", snapshot.warpActiveProfileId)
        }
}

private class BackupBootSessionStateStore : BootSessionStateStore {
    override fun lastSession(): BootSessionPointer? = null

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) = Unit

    override fun clear() = Unit

    override fun wasRunningAtUpdate(): Boolean = false

    override fun setWasRunningAtUpdate(value: Boolean) = Unit
}

private class BackupRelayCredentialStore : RelayCredentialStore {
    private val records = mutableMapOf<String, RelayCredentialRecord>()

    override suspend fun load(profileId: String): RelayCredentialRecord? = records[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        records[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }

    override suspend fun clearAll() = records.clear()
}

private class BackupWarpCredentialStore : WarpCredentialStore {
    private val records = mutableMapOf<String, WarpCredentials>()

    override suspend fun load(profileId: String): WarpCredentials? = records[profileId]

    override suspend fun loadAll(): List<WarpCredentials> = records.values.sortedBy { it.profileId }

    override suspend fun save(
        profileId: String,
        credentials: WarpCredentials,
    ) {
        records[profileId] = credentials.copy(profileId = profileId)
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }

    override suspend fun clearAll() = records.clear()
}

private class BackupAwgCredentialStore : com.poyka.ripdpi.data.awg.AwgCredentialStore {
    private val records = mutableMapOf<String, AwgSecrets>()

    override suspend fun load(profileId: String): AwgSecrets? = records[profileId]

    override suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) {
        records[profileId] = secrets
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }

    override suspend fun clearAll() = records.clear()
}

private class BackupXraySecretStore : XrayProfileSecretStore {
    private val records = mutableMapOf<String, XrayProfileSecretRecord>()

    override suspend fun load(profileId: String): XrayProfileSecretRecord? = records[profileId]

    override suspend fun save(record: XrayProfileSecretRecord) {
        records[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }

    override suspend fun clearAll() = records.clear()
}

private class BackupSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)
    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot() = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class BackupProfileMutationJournal : ProfileMutationJournal {
    private var value: PendingProfileMutation? = null

    override suspend fun prepare(mutation: PendingProfileMutation) {
        check(value == null)
        value = mutation
    }

    override suspend fun pending() = value

    override suspend fun replace(
        expectedMutationId: String,
        mutation: PendingProfileMutation,
    ) {
        check(value?.mutationId == expectedMutationId)
        value = mutation
    }

    override suspend fun complete(mutationId: String) {
        check(value?.mutationId == mutationId)
        value = null
    }

    override suspend fun clearForReset() {
        value = null
    }
}
