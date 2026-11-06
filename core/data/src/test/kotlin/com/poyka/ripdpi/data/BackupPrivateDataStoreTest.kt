package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.AwgBackupProfile
import com.poyka.ripdpi.data.backup.BackupPrivateDataStore
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.data.testing.backupPrivateDataStore
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
class BackupPrivateDataStoreTest {
    private lateinit var fixture: RoomProfileFixture
    private lateinit var store: BackupPrivateDataStore

    @Before
    fun setUp() =
        runTest {
            fixture =
                RoomProfileFixture(
                    ApplicationProvider.getApplicationContext<Context>(),
                    testPauseAuthority(initialize = false),
                )
            fixture.initialize()
            store = fixture.backupPrivateDataStore()
        }

    @After
    fun tearDown() = fixture.close()

    private suspend fun preparation() = fixture.coordinator.captureMutation(ProfileMutationOrigin.RestoreProfiles)

    @Test
    fun `replaceAll round-trips every private profile family and clears endpoint cache`() =
        runTest {
            fixture.warpEndpoints.save(
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

            store.replaceAll(
                preparation(),
                expected,
            )

            assertEquals(expected, store.snapshot())
            assertTrue(fixture.warpEndpoints.loadAll("old-warp").isEmpty())
        }

    @Test
    fun `snapshot exports readable profiles and leaves out secrets that no longer decrypt`() =
        runTest {
            val stored =
                BackupPrivateDataV1(
                    relayProfiles = listOf(RelayProfileRecord(id = "relay-ok"), RelayProfileRecord(id = "relay-lost")),
                    relayCredentials =
                        listOf(
                            RelayCredentialRecord(profileId = "relay-ok", vlessUuid = "uuid-ok"),
                            RelayCredentialRecord(profileId = "relay-lost", vlessUuid = "uuid-lost"),
                        ),
                    awgProfiles =
                        listOf(
                            AwgBackupProfile(
                                id = "awg-lost",
                                name = "AWG",
                                requestJson = "{}",
                                updatedAt = 7L,
                                secrets = AwgSecrets(privateKey = "private"),
                            ),
                        ),
                    xrayMetadata =
                        listOf(
                            XrayProfileMetadataRecord(profileId = "xray-ok", revision = "fixture", name = "Ok"),
                            XrayProfileMetadataRecord(profileId = "xray-lost", revision = "fixture", name = "Lost"),
                        ),
                    xraySecrets =
                        listOf(
                            XrayProfileSecretRecord(profileId = "xray-ok", revision = "fixture", uuid = "a"),
                            XrayProfileSecretRecord(profileId = "xray-lost", revision = "fixture", uuid = "b"),
                        ),
                    xraySelection =
                        XrayProviderSelectionRecord(XrayProviderSelectionRecord.ProviderKindXray, "xray-lost"),
                )
            store.replaceAll(
                preparation(),
                stored,
            )
            fixture.beforeOpen = { namespace, id ->
                if (id in setOf("relay-lost", "awg-lost", "xray-lost") &&
                    namespace in setOf("relay_credentials", "awg_credentials", "xray_secrets")
                ) {
                    throw GeneralSecurityException("Fixture key unavailable")
                }
            }

            val snapshot = store.snapshot()

            assertEquals(setOf("relay-ok", "relay-lost"), snapshot.relayProfiles.map { it.id }.toSet())
            assertEquals(listOf("relay-ok"), snapshot.relayCredentials.map { it.profileId })
            assertEquals(null, snapshot.awgProfiles.single().secrets)
            assertEquals(listOf("xray-ok"), snapshot.xrayMetadata.map { it.profileId })
            assertEquals(listOf("xray-ok"), snapshot.xraySecrets.map { it.profileId })
            assertEquals(XrayProviderSelectionRecord(), snapshot.xraySelection)
        }

    @Test
    fun `replace rejects an unknown selected provider before writing`() =
        runTest {
            val before = store.snapshot()
            val result =
                runCatching {
                    store.replaceAll(
                        preparation(),
                        BackupPrivateDataV1(xraySelection = XrayProviderSelectionRecord(providerKind = "unknown")),
                    )
                }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, store.snapshot())
        }

    @Test
    fun `snapshot rejects an incomplete Xray pair without retrying reads`() =
        runTest {
            fixture.xrayMetadata.save(XrayProfileMetadataRecord(profileId = "xray-1", revision = "old"))
            fixture.xraySecrets.save(XrayProfileSecretRecord(profileId = "xray-1", revision = "new"))

            val result = runCatching { store.snapshot() }

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals("old", fixture.xrayMetadata.load("xray-1")?.revision)
            assertEquals("new", fixture.xraySecrets.load("xray-1")?.revision)
        }

    @Test
    fun `snapshot rejects an active WARP id without a profile`() =
        runTest {
            fixture.warpProfiles.setActiveProfileId("missing")

            val result = runCatching { store.snapshot() }

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals("missing", fixture.warpProfiles.activeProfileId())
        }

    @Test
    fun `failed private replacement keeps the old snapshot after reopen`() =
        runTest {
            val before =
                BackupPrivateDataV1(
                    relayProfiles = listOf(RelayProfileRecord(id = "old")),
                    relayCredentials =
                        listOf(
                            RelayCredentialRecord(
                                profileId = "old",
                                vlessUuid = "Fixture old",
                                updatedAtEpochMillis = 1,
                            ),
                        ),
                )
            store.replaceAll(preparation(), before)
            val preparation = preparation()
            var writes = 0
            fixture.beforeSeal = { _, _ ->
                writes += 1
                check(writes != 2) { "Fixture write failure" }
            }

            val failure =
                runCatching {
                    store.replaceAll(
                        preparation,
                        BackupPrivateDataV1(relayProfiles = listOf(RelayProfileRecord(id = "new"))),
                    )
                }

            assertTrue(failure.isFailure)
            assertEquals(2, writes)
            fixture.beforeSeal = null
            assertEquals(before, store.snapshot())
            fixture = fixture.reopen()
            store = fixture.backupPrivateDataStore()
            assertEquals(before, store.snapshot())
        }
}
