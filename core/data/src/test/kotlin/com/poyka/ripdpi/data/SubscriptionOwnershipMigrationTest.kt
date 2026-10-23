package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.serialization.RipDpiContractJson
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionOwnershipMigrationTest {
    @Test
    fun `legacy catalogue commits ownership before publication and keeps it across cold reads`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val blob = VersionedOwnershipBlob(LegacyOwnershipCatalogue, version = 1)
            val groups = ownershipRepository(fixture, blob)
            groups.groups().test {
                expectNoEvents()
                blob.beforeWrite = {
                    expectNoEvents()
                    assertEquals(1, blob.version)
                    assertEquals(LegacyOwnershipCatalogue, blob.encoded)
                }

                val migrated = groups.list()

                assertEquals(ProxyGroupSchema.SCHEMA_VERSION, blob.version)
                assertEquals(migrated, decodeOwnershipGroups(blob.encoded))
                assertEquals(migrated, awaitItem())
                assertTrue(migrated.all { it.ownershipGeneration.isNotBlank() })
                assertEquals(migrated.size, migrated.map { it.ownershipGeneration }.distinct().size)
                assertEquals(
                    decodeOwnershipGroups(LegacyOwnershipCatalogue),
                    migrated.map { it.copy(ownershipGeneration = "") },
                )
                val durable = blob.encoded
                assertEquals(migrated, groups.list())
                assertEquals(migrated, ownershipRepository(fixture, blob).list())
                assertEquals(durable, blob.encoded)
                assertEquals(1, blob.writeAttempts)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `failed catalogue migration keeps legacy bytes and never publishes an ephemeral generation`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val blob = VersionedOwnershipBlob(LegacyOwnershipCatalogue, version = 1)
            val groups = ownershipRepository(fixture, blob)
            blob.failWrite = true
            groups.groups().test {
                assertEquals("Catalogue commit failed", runCatching { groups.list() }.exceptionOrNull()?.message)

                assertEquals(LegacyOwnershipCatalogue, blob.encoded)
                assertEquals(1, blob.version)
                assertEquals(1, blob.writeAttempts)
                expectNoEvents()
                blob.failWrite = false
                val migrated = groups.list()

                assertEquals(migrated, awaitItem())
                assertTrue(migrated.all { it.ownershipGeneration.isNotBlank() })
                assertEquals(migrated, decodeOwnershipGroups(blob.encoded))
                assertEquals(migrated, ownershipRepository(fixture, blob).list())
                assertEquals(2, blob.writeAttempts)
                assertNoOwnershipAwgWrites(fixture)
                assertNull(fixture.journal.pending())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `interrupted migration after durable catalogue commit reuses that generation on cold reload`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val blob = VersionedOwnershipBlob(LegacyOwnershipCatalogue, version = 1)
            val groups = ownershipRepository(fixture, blob)
            blob.afterWrite = { throw CancellationException("Interrupted after catalogue commit") }
            groups.groups().test {
                assertTrue(runCatching { groups.list() }.exceptionOrNull() is CancellationException)

                assertEquals(ProxyGroupSchema.SCHEMA_VERSION, blob.version)
                val committed = decodeOwnershipGroups(blob.encoded)
                assertTrue(committed.all { it.ownershipGeneration.isNotBlank() })
                expectNoEvents()
                blob.afterWrite = null

                assertEquals(committed, ownershipRepository(fixture, blob).list())
                assertEquals(committed, groups.list())
                assertEquals(committed, awaitItem())
                assertEquals(1, blob.writeAttempts)
                assertEquals(committed, decodeOwnershipGroups(blob.encoded))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `legacy journal is durably upgraded before AWG and cold retry keeps its captured generations`() =
        runTest {
            val seeded = seedLegacyOwnershipJournal()
            val fixture = seeded.fixture
            var replacements = 0
            var upgraded: PendingProfileMutation? = null
            fixture.journal.beforeReplace = {
                assertNoOwnershipAwgWrites(fixture)
                replacements++
            }
            fixture.awgCredentials.afterSave = {
                upgraded = checkNotNull(fixture.journal.pending())
                assertEquals(1, decodeOwnershipIntent(checkNotNull(upgraded)).ownershipVersion)
                throw CancellationException("Interrupted after migrated credential")
            }
            val first = seeded.coldOwner()
            first.groups.groups().test {
                assertTrue(runCatching { first.mutations.recover() }.exceptionOrNull() is CancellationException)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            val migrated = checkNotNull(upgraded)
            val intent = decodeOwnershipIntent(migrated)
            assertEquals(seeded.pending.copy(payload = migrated.payload), migrated)
            assertEquals(migrated, fixture.journal.pending())
            assertNotEquals(seeded.pending.payload, migrated.payload)
            assertTrue(intent.groups.all { it.ownershipGeneration.isNotBlank() })
            assertEquals(
                intent.groups.size,
                intent.groups
                    .map { it.ownershipGeneration }
                    .distinct()
                    .size,
            )
            assertEquals(seeded.intent.groups, intent.groups.map { it.copy(ownershipGeneration = "") })
            assertEquals(seeded.intent.awgProfiles, intent.awgProfiles)
            assertEquals(1, replacements)
            assertEquals(1, fixture.awgCredentials.saveAttempts)
            assertEquals(0, fixture.awgProfiles.upsertAttempts)
            assertEquals(seeded.beforeGroups, fixture.groupCatalog.snapshot())
            fixture.awgCredentials.afterSave = null
            val restarted = seeded.coldOwner()
            restarted.groups.groups().test {
                expectNoEvents()
                restarted.mutations.recover()

                assertEquals(intent.groups, awaitItem())
                seeded.assertApplied(intent)
                assertEquals(1, replacements)
                val writes = ownershipWriteCounts(fixture)
                restarted.mutations.recover()
                assertEquals(writes, ownershipWriteCounts(fixture))
                assertEquals(intent.groups, ProxyGroupCatalog(fixture.groupBlob).snapshot())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `failed journal replacement preserves the original record and every store until retry`() =
        runTest {
            val seeded = seedLegacyOwnershipJournal()
            val fixture = seeded.fixture
            fixture.journal.beforeReplace = { error("Journal replacement failed") }
            val cold = seeded.coldOwner()
            cold.groups.groups().test {
                assertEquals(
                    "Journal replacement failed",
                    runCatching { cold.mutations.recover() }.exceptionOrNull()?.message,
                )

                assertEquals(seeded.pending, fixture.journal.pending())
                assertEquals(seeded.beforeGroups, fixture.groupCatalog.snapshot())
                assertEquals(seeded.beforeRow, fixture.awgProfiles.getProfile(seeded.beforeRow.id))
                assertEquals(seeded.beforeSecrets, fixture.awgCredentials.load(seeded.beforeRow.id))
                assertNoOwnershipAwgWrites(fixture)
                assertEquals(0, fixture.groupWriteAttempts)
                assertEquals(fixture.pauseAuthority.states.value, cold.authority.states.value)
                expectNoEvents()
                fixture.journal.beforeReplace = null
                var accepted: SubscriptionRefreshIntent? = null
                fixture.awgCredentials.afterSave = {
                    accepted = decodeOwnershipIntent(checkNotNull(fixture.journal.pending()))
                }
                cold.mutations.recover()

                val intent = checkNotNull(accepted)
                assertEquals(1, intent.ownershipVersion)
                assertEquals(intent.groups, awaitItem())
                seeded.assertApplied(intent)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `malformed current catalogue blocks journal migration and AWG replay without publication`() =
        runTest {
            val seeded = seedLegacyOwnershipJournal()
            val fixture = seeded.fixture
            val blob = VersionedOwnershipBlob(LegacyOwnershipCatalogue, version = ProxyGroupSchema.SCHEMA_VERSION)
            val catalog = ProxyGroupCatalog(blob)
            val authority = testPauseAuthority(fixture.persistence, initialize = false)
            val mutations = fixture.coordinator(catalog = catalog, authority = authority)
            val groups = SharedPreferencesProxyGroupRepository(catalog, mutations)
            var replacements = 0
            fixture.journal.beforeReplace = { replacements++ }
            groups.groups().test {
                assertTrue(runCatching { mutations.recover() }.exceptionOrNull() is IllegalArgumentException)

                assertEquals(seeded.pending, fixture.journal.pending())
                assertEquals(LegacyOwnershipCatalogue, blob.encoded)
                assertEquals(0, blob.writeAttempts)
                assertEquals(0, replacements)
                assertNoOwnershipAwgWrites(fixture)
                assertEquals(fixture.pauseAuthority.states.value, authority.states.value)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `current journal rejects missing ownership in either group and unsupported versions before AWG`() =
        runTest {
            for (invalid in 0..2) {
                val seeded = seedLegacyOwnershipJournal()
                val fixture = seeded.fixture
                val groups =
                    seeded.beforeGroups.mapIndexed { index, group ->
                        if (index == invalid) group.copy(ownershipGeneration = " ") else group
                    }
                val intent = seeded.intent.copy(groups = groups, ownershipVersion = if (invalid == 2) 2 else 1)
                val pending =
                    seeded.pending.copy(
                        payload = RipDpiContractJson.encodeToString(ProfileMutationIntent.serializer(), intent),
                    )
                fixture.journal.replace(seeded.pending.mutationId, pending)
                var replacements = 0
                fixture.journal.beforeReplace = { replacements++ }
                val cold = seeded.coldOwner()
                cold.groups.groups().test {
                    assertTrue(runCatching { cold.mutations.recover() }.exceptionOrNull() is IllegalStateException)

                    assertEquals(pending, fixture.journal.pending())
                    assertEquals(seeded.beforeGroups, fixture.groupCatalog.snapshot())
                    assertNoOwnershipAwgWrites(fixture)
                    assertEquals(0, fixture.groupWriteAttempts)
                    assertEquals(0, replacements)
                    assertEquals(fixture.pauseAuthority.states.value, cold.authority.states.value)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
}

/** In-memory persistence adapter; production schema code decides whether a write is required. */
private class VersionedOwnershipBlob(
    var encoded: String,
    var version: Int,
) : ProxyGroupBlobStore {
    var writeAttempts = 0
    var failWrite = false
    var beforeWrite: (() -> Unit)? = null
    var afterWrite: (() -> Unit)? = null

    override fun read(): String = ProxyGroupSchema.read(encoded, version, ::write)

    override fun write(json: String) {
        writeAttempts++
        beforeWrite?.invoke()
        check(!failWrite) { "Catalogue commit failed" }
        encoded = json
        version = ProxyGroupSchema.SCHEMA_VERSION
        afterWrite?.invoke()
    }

    override fun clear() = error("Ownership migration must not clear persisted groups")
}

private data class OwnershipRecoveryOwner(
    val mutations: ProfileMutationRecoveryCoordinator,
    val groups: SharedPreferencesProxyGroupRepository,
    val authority: PauseIntentAuthority,
)

private data class LegacyOwnershipJournal(
    val fixture: ProfileMutationTestFixture,
    val pending: PendingProfileMutation,
    val intent: SubscriptionRefreshIntent,
    val beforeGroups: List<ProxyGroup>,
    val beforeRow: AwgProfileEntity,
    val beforeSecrets: AwgSecrets,
) {
    fun coldOwner(): OwnershipRecoveryOwner {
        val catalog = ProxyGroupCatalog(fixture.groupBlob)
        val authority = testPauseAuthority(fixture.persistence, initialize = false)
        val mutations = fixture.coordinator(catalog = catalog, authority = authority)
        return OwnershipRecoveryOwner(mutations, SharedPreferencesProxyGroupRepository(catalog, mutations), authority)
    }

    suspend fun assertApplied(accepted: SubscriptionRefreshIntent) {
        assertEquals(accepted.groups, ProxyGroupCatalog(fixture.groupBlob).snapshot())
        val row = accepted.awgProfiles.single()
        assertEquals(beforeRow.id, row.id)
        assertEquals(beforeSecrets.privateKey, row.secrets.privateKey)
        assertEquals(row.toEntity(), fixture.awgProfiles.getProfile(row.id))
        assertEquals(row.secrets, fixture.awgCredentials.load(row.id))
        assertNull(fixture.journal.pending())
        assertEquals(0, fixture.prepareAttempts)
    }
}

private suspend fun seedLegacyOwnershipJournal(): LegacyOwnershipJournal {
    val fixture = ProfileMutationTestFixture()
    val mutations = fixture.coordinator()
    val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
    decodeOwnershipGroups(LegacyOwnershipCatalogue).forEach { groups.add(it) }
    val beforeGroups = groups.list()
    val awg = AwgProfileRepository(fixture.awgProfiles, fixture.awgCredentials, mutations)
    val request =
        AwgActivationRequest(
            profileId = "",
            privateKey = ownershipTestKey(1),
            peerPublicKey = ownershipTestKey(2),
            presharedKey = ownershipTestKey(3),
            endpointHost = "192.0.2.10",
            endpointPort = 443,
            interfaceAddressV4 = "10.8.0.2/32",
        )
    val incoming = AwgSubscriptionProfile("subscription", "member", "Member", request)
    val saved = awg.saveSubscriptionProfiles(listOf(incoming)).single()
    val row = checkNotNull(fixture.awgProfiles.getProfile(saved.id))
    val secrets = checkNotNull(fixture.awgCredentials.load(saved.id))
    val requestJson = RipDpiContractJson.parseToJsonElement(row.requestJson).jsonObject
    val rotated =
        AwgUpsertIntent(
            row.id,
            "Refreshed member",
            JsonObject(requestJson + ("endpointHost" to JsonPrimitive("192.0.2.20"))).toString(),
            row.updatedAt + 1,
            secrets.copy(presharedKey = ownershipTestKey(4)),
        )
    val intent =
        SubscriptionRefreshIntent(
            beforeGroups.map { group ->
                group.copy(
                    ownershipGeneration = "",
                    subscription = group.subscription?.copy(lastUpdated = 2_000L),
                )
            },
            listOf(rotated),
        )
    val projected =
        ProfileMutationIntentProjection().pending(
            ProfileMutationOrigin.SubscriptionRefresh,
            fixture.pauseAuthority.reference(),
            intent,
        )
    val payload = RipDpiContractJson.parseToJsonElement(projected.payload).jsonObject
    val legacyGroups = payload.getValue("groups").jsonArray.map { JsonObject(it.jsonObject - "ownershipGeneration") }
    val pending =
        projected.copy(
            payload = JsonObject((payload - "ownershipVersion") + ("groups" to JsonArray(legacyGroups))).toString(),
        )
    fixture.journal.prepare(pending)
    fixture.prepareAttempts = 0
    fixture.awgCredentials.saveAttempts = 0
    fixture.awgProfiles.upsertAttempts = 0
    fixture.groupWriteAttempts = 0
    return LegacyOwnershipJournal(fixture, pending, intent, beforeGroups, row, secrets)
}

private fun ownershipRepository(
    fixture: ProfileMutationTestFixture,
    blob: ProxyGroupBlobStore,
): SharedPreferencesProxyGroupRepository {
    val catalog = ProxyGroupCatalog(blob)
    return SharedPreferencesProxyGroupRepository(catalog, fixture.coordinator(catalog = catalog))
}

private fun decodeOwnershipGroups(json: String): List<ProxyGroup> =
    RipDpiJson.decodeFromString(ListSerializer(ProxyGroup.serializer()), json)

private fun decodeOwnershipIntent(pending: PendingProfileMutation): SubscriptionRefreshIntent =
    RipDpiContractJson.decodeFromString(
        ProfileMutationIntent.serializer(),
        pending.payload,
    ) as SubscriptionRefreshIntent

private fun assertNoOwnershipAwgWrites(fixture: ProfileMutationTestFixture) {
    assertEquals(0, fixture.awgCredentials.saveAttempts)
    assertEquals(0, fixture.awgProfiles.upsertAttempts)
}

private fun ownershipWriteCounts(fixture: ProfileMutationTestFixture): List<Int> =
    listOf(
        fixture.prepareAttempts,
        fixture.awgCredentials.saveAttempts,
        fixture.awgProfiles.upsertAttempts,
        fixture.groupWriteAttempts,
    )

private fun ownershipTestKey(value: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { value.toByte() })

private val LegacyOwnershipCatalogue =
    """
    [
      {"id":"subscription","name":"Subscription","type":"SUBSCRIPTION","order":0,"isSelector":false,
       "subscription":{"link":"https://subscription.example/config","refreshRevision":7}},
      {"id":"unrelated","name":"Unrelated","type":"BASIC","order":1,"isSelector":false}
    ]
    """.trimIndent()
