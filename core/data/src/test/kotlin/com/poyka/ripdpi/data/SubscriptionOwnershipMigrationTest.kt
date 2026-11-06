package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.awg.KeystoreAwgCredentialStore
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionOwnershipMigrationTest : RoomProfileTest() {
    @Test
    fun `legacy catalogue ownership is committed once and survives cold reads`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.seedGroups(LegacyOwnershipCatalogue)
                legacy.fixture.initialize()
                val migrated = legacy.fixture.groups.list()
                assertTrue(migrated.all { it.ownershipGeneration.isNotBlank() })
                assertEquals(migrated.size, migrated.map { it.ownershipGeneration }.distinct().size)
                assertEquals(decode(LegacyOwnershipCatalogue), migrated.map { it.copy(ownershipGeneration = "") })
                val committed = legacy.fixture.snapshot()
                legacy.fixture.reopen()
                assertEquals(migrated, legacy.fixture.groups.list())
                assertEquals(committed, legacy.fixture.snapshot())
            }
        }

    @Test
    fun `failed ownership import keeps legacy bytes and emits no uncommitted groups`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.seedGroups(LegacyOwnershipCatalogue)
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                legacy.fixture.failWrite("proxy_groups")
                legacy.fixture.groups.groups().test {
                    val failure = runCatching { legacy.fixture.initialize() }.exceptionOrNull()
                    assertTrue(failure is IllegalStateException)
                    expectNoEvents()
                    assertEquals(source, legacy.legacyBytes())
                    assertEquals(before, legacy.fixture.snapshot())
                    legacy.fixture.clearFaults()
                    legacy.fixture.initialize()
                    val migrated = awaitItem()
                    assertTrue(migrated.all { it.ownershipGeneration.isNotBlank() })
                    assertEquals(migrated, legacy.fixture.groups.list())
                }
            }
        }

    @Test
    fun `cancellation before import marker rolls back ownership and retry commits one durable generation`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.seedGroups(LegacyOwnershipCatalogue)
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                val barrier = ProfileWriteBarrier("system")
                legacy.fixture.room.beforeSeal = barrier::beforeSeal
                val importing = async { legacy.fixture.initialize() }
                try {
                    barrier.awaitEntry()
                    importing.cancel()
                } finally {
                    barrier.release()
                }
                importing.join()
                assertEquals(source, legacy.legacyBytes())
                assertEquals(before, legacy.fixture.snapshot())
                legacy.fixture.clearFaults()
                legacy.fixture.initialize()
                val accepted = legacy.fixture.groups.list()
                legacy.fixture.reopen()
                assertEquals(accepted, legacy.fixture.groups.list())
            }
        }

    @Test
    fun `legacy journal ownership and AWG rotation commit together after a failed marker retry`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                val pending = seedLegacyJournal(legacy)
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                legacy.fixture.failWrite("system")
                assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                assertEquals(source, legacy.legacyBytes())
                assertEquals(pending, legacy.journal.pending())
                assertEquals(before, legacy.fixture.snapshot())
                legacy.fixture.clearFaults()
                legacy.fixture.initialize()
                assertAccepted(legacy)
                val committed = legacy.fixture.snapshot()
                legacy.fixture.reopen()
                assertAccepted(legacy)
                assertEquals(committed, legacy.fixture.snapshot())
            }
        }

    @Test
    fun `SQL failure after imported secret preserves legacy journal and old AWG until retry`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                seedLegacyJournal(legacy)
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                withContext(Dispatchers.IO) {
                    legacy.fixture.room.database.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER import_failure BEFORE INSERT ON awg_profiles " +
                            "BEGIN SELECT RAISE(ABORT, 'import failed'); END",
                    )
                }
                assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                assertEquals(source, legacy.legacyBytes())
                assertEquals(before, legacy.fixture.snapshot())
                withContext(Dispatchers.IO) {
                    legacy.fixture.room.database.openHelper.writableDatabase
                        .execSQL("DROP TRIGGER import_failure")
                }
                legacy.fixture.initialize()
                assertAccepted(legacy)
            }
        }

    @Test
    fun `malformed current catalogue blocks pending ownership migration without modifying source`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                seedLegacyJournal(legacy)
                legacy.seedGroups(LegacyOwnershipCatalogue, ProxyGroupSchema.SCHEMA_VERSION)
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                assertEquals(source, legacy.legacyBytes())
                assertEquals(before, legacy.fixture.snapshot())
            }
        }

    @Test
    fun `current journal rejects missing group ownership and unsupported versions before any writes`() =
        runTest {
            for (invalid in 0..2) {
                LegacyProfileImportFixture().use { legacy ->
                    val groups =
                        decode(LegacyOwnershipCatalogue).mapIndexed { index, group ->
                            group.copy(ownershipGeneration = if (index == invalid) " " else "owner-$index")
                        }
                    legacy.seed(
                        SubscriptionRefreshIntent(
                            groups,
                            emptyList(),
                            ownershipVersion =
                                if (invalid ==
                                    2
                                ) {
                                    2
                                } else {
                                    1
                                },
                        ),
                    )
                    val source = legacy.legacyBytes()
                    val before = legacy.fixture.snapshot()
                    assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                    assertEquals(source, legacy.legacyBytes())
                    assertEquals(before, legacy.fixture.snapshot())
                }
            }
        }

    private suspend fun seedLegacyJournal(legacy: LegacyProfileImportFixture): PendingProfileMutation {
        legacy.seedGroups(LegacyOwnershipCatalogue)
        withContext(Dispatchers.IO) {
            legacy.fixture.awgProfiles.upsertProfile(AwgProfileEntity("one", "Original", "{}", 1))
        }
        KeystoreAwgCredentialStore(legacy.context).save("one", AwgSecrets("old-private", "old-psk"))
        val intent =
            SubscriptionRefreshIntent(
                decode(LegacyOwnershipCatalogue).map {
                    it.copy(subscription = it.subscription?.copy(lastUpdated = 2000L))
                },
                listOf(AwgUpsertIntent("one", "Rotated", "{}", 2L, AwgSecrets("old-private", "new-psk"))),
            )
        return legacy.seed(intent)
    }

    private suspend fun assertAccepted(legacy: LegacyProfileImportFixture) {
        val groups = legacy.fixture.groups.list()
        assertTrue(groups.all { it.ownershipGeneration.isNotBlank() })
        assertEquals(groups.size, groups.map { it.ownershipGeneration }.distinct().size)
        assertEquals(2000L, groups.first { it.id == "subscription" }.subscription?.lastUpdated)
        assertEquals(AwgSecrets("old-private", "new-psk"), legacy.fixture.awgCredentials.load("one"))
        assertEquals(
            AwgProfileEntity("one", "Rotated", "{}", 2L),
            withContext(Dispatchers.IO) {
                legacy.fixture.awgProfiles.getProfile("one")
            },
        )
        assertNull(legacy.journal.pending())
    }

    private fun decode(json: String) = RipDpiJson.decodeFromString(ListSerializer(ProxyGroup.serializer()), json)
}

private val LegacyOwnershipCatalogue =
    """
    [
      {"id":"subscription","name":"Subscription","type":"SUBSCRIPTION","order":0,"isSelector":false,
       "subscription":{"link":"https://subscription.example/config","refreshRevision":7}},
      {"id":"unrelated","name":"Unrelated","type":"BASIC","order":1,"isSelector":false}
    ]
    """.trimIndent()
