package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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

class SubscriptionHealthPolicyJournalMigrationTest {
    @Test
    fun `health journal upgrade preserves provenance ownership and unrelated raw fields exactly once`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val pending = seed(fixture)
            var replacements = 0
            fixture.journal.beforeReplace = { replacements++ }

            val recovered = checkNotNull(reader(fixture).read())
            val intent = recovered.intent as SubscriptionRefreshIntent

            assertEquals(pending.copy(payload = recovered.pending.payload), recovered.pending)
            assertEquals("captured-owner", intent.groups.single().ownershipGeneration)
            assertEquals(
                2,
                intent.groups
                    .single()
                    .failover!!
                    .payloadPolicy!!
                    .schemaVersion,
            )
            assertNull(
                intent.groups
                    .single()
                    .failover!!
                    .payloadPolicy!!
                    .quality,
            )
            assertEquals(expectedUpgrade(pending.payload), parse(recovered.pending.payload))
            assertEquals(recovered, reader(fixture).read())
            assertEquals(1, replacements)
            assertNoReplay(fixture)
        }

    @Test
    fun `failed checked journal write retains legacy bytes and prevents every replay store`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val pending = seed(fixture)
            fixture.journal.beforeReplace = { error("Journal commit failed") }

            assertEquals(
                "Journal commit failed",
                runCatching { fixture.coordinator().recover() }.exceptionOrNull()?.message,
            )
            assertEquals(pending, fixture.journal.pending())
            assertNoReplay(fixture)

            fixture.journal.beforeReplace = null
            fixture
                .coordinator(
                    catalog = ProxyGroupCatalog(fixture.groupBlob),
                    authority = testPauseAuthority(fixture.persistence, initialize = false),
                ).recover()
            assertNull(fixture.journal.pending())
            assertEquals(
                "captured-owner",
                fixture.groupCatalog
                    .snapshot()
                    .single()
                    .ownershipGeneration,
            )
        }

    @Test
    fun `interrupted replay restarts from committed migration then accepts a quality refresh`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val pending = seed(fixture)
            var replacements = 0
            fixture.journal.beforeReplace = { replacements++ }
            fixture.beforeGroupWrite = { throw CancellationException("Interrupted before catalogue commit") }

            assertTrue(runCatching { fixture.coordinator().recover() }.exceptionOrNull() is CancellationException)
            val durable = checkNotNull(fixture.journal.pending())
            assertNotEquals(pending.payload, durable.payload)
            assertEquals(expectedUpgrade(pending.payload), parse(durable.payload))
            fixture.beforeGroupWrite = null
            val catalog = ProxyGroupCatalog(fixture.groupBlob)
            val restarted =
                fixture.coordinator(
                    catalog = catalog,
                    authority = testPauseAuthority(fixture.persistence, initialize = false),
                )
            restarted.recover()
            assertEquals(1, replacements)
            assertNull(fixture.journal.pending())
            val current = catalog.snapshot().single()
            assertEquals("captured-owner", current.ownershipGeneration)
            val quality = ProfileQualityPolicy(131_076, 262_152, 300, 20, 100)
            val failover = requireNotNull(current.failover)
            val policy = requireNotNull(failover.payloadPolicy)
            val next = current.copy(failover = failover.copy(payloadPolicy = policy.copy(quality = quality)))

            assertTrue(restarted.commitSubscriptionRefresh(current) { PreparedSubscriptionRefresh(next, emptyList()) })
            val stored = ProxyGroupCatalog(fixture.groupBlob).snapshot().single()
            assertEquals(quality, stored.failover!!.payloadPolicy!!.quality)
            assertNotEquals(current.ownershipGeneration, stored.ownershipGeneration)
            assertNull(fixture.journal.pending())
        }

    @Test
    fun `legacy ownership and health upgrade share one checked write without dropping unknown fields`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val pending = seed(fixture, ownershipVersion = 0)
            var replacements = 0
            fixture.journal.beforeReplace = { replacements++ }
            val accepted = checkNotNull(reader(fixture).read())
            val intent = accepted.intent as SubscriptionRefreshIntent
            assertEquals(1, intent.ownershipVersion)
            val owner = intent.groups.single().ownershipGeneration
            assertTrue(owner.isNotBlank())
            val expected = expectedUpgrade(pending.payload)
            val groups =
                expected.getValue("groups").jsonArray.map {
                    JsonObject(it.jsonObject + ("ownershipGeneration" to JsonPrimitive(owner)))
                }
            assertEquals(
                JsonObject(expected + mapOf("groups" to JsonArray(groups), "ownershipVersion" to JsonPrimitive(1))),
                parse(accepted.pending.payload),
            )
            assertEquals(accepted, reader(fixture).read())
            assertEquals(1, replacements)
        }

    @Test
    fun `invalid legacy policy or ownership never replaces the original journal`() =
        runTest {
            val changes =
                listOf(
                    LegacyPayload.replace("32768", "65537"),
                    LegacyPayload.replace("\"schema_version\":1", "\"schema_version\":1,\"quality\":null"),
                    LegacyPayload.replace("\"schema_version\":1", "\"schema_version\":1,\"unknown_policy\":true"),
                    LegacyPayload.replace("\"ownershipVersion\":1", "\"ownershipVersion\":2"),
                    LegacyPayload.replace("captured-owner", " "),
                    LegacyPayload.replace("\"awgProfiles\":[]", "\"awgProfiles\":[{}]"),
                )
            for (payload in changes) {
                val fixture = ProfileMutationTestFixture()
                val pending = seed(fixture, payload = payload)
                var replacements = 0
                fixture.journal.beforeReplace = { replacements++ }
                assertTrue(runCatching { fixture.coordinator().recover() }.isFailure)
                assertEquals(pending, fixture.journal.pending())
                assertEquals(0, replacements)
                assertNoReplay(fixture)
            }
        }

    private fun reader(fixture: ProfileMutationTestFixture) =
        ProfileMutationJournalRecoveryReader(fixture.journal, ProxyGroupCatalog(fixture.groupBlob))

    private suspend fun seed(
        fixture: ProfileMutationTestFixture,
        ownershipVersion: Int = 1,
        payload: String = LegacyPayload,
    ): PendingProfileMutation {
        fixture.pauseAuthority.initializeAfterMigration()
        val pending =
            PendingProfileMutation(
                mutationId = "captured-refresh",
                family = ProfileMutationFamily.Subscription,
                payload = payload.replace("\"ownershipVersion\":1", "\"ownershipVersion\":$ownershipVersion"),
                origin = ProfileMutationOrigin.SubscriptionRefresh,
                expectedPauseAuthority = fixture.pauseAuthority.reference(),
            )
        fixture.journal.prepare(pending)
        return pending
    }

    private fun assertNoReplay(fixture: ProfileMutationTestFixture) {
        assertEquals(0, fixture.storeWriteAttempts)
        assertEquals(0, fixture.groupWriteAttempts)
        assertEquals(0, fixture.awgCredentials.saveAttempts)
        assertEquals(0, fixture.awgProfiles.upsertAttempts)
    }

    private fun parse(payload: String) = RipDpiContractJson.parseToJsonElement(payload).jsonObject

    private fun expectedUpgrade(payload: String) =
        parse(payload.replace("\"schema_version\":1", "\"schema_version\":2"))
}

private val LegacyPayload =
    """
    {"type":"subscription_refresh","ownershipVersion":1,"awgProfiles":[],"futurePayload":{"retain":[1,null,"value"]},
     "groups":[{"id":"subscription","name":"Subscription","type":"SUBSCRIPTION","order":0,"isSelector":true,
      "ownershipGeneration":"captured-owner","futureGroup":{"retain":true},
      "subscription":{"link":"https://subscription.example/config","refreshRevision":7},
      "failover":{"probeUrl":"https://first.example/health","intervalSeconds":60,"toleranceMs":20,"futureFailover":7,
       "payloadPolicy":{"schema_version":1,"selector_tag":"select","probes":[
         {"url":"https://first.example/payload","expected_bytes":32768,"sha256":"${"a".repeat(64)}"},
         {"url":"https://second.example/payload","expected_bytes":32768,"sha256":"${"b".repeat(64)}"}],
        "interval_seconds":60,"timeout_seconds":10,"failure_threshold":2,"recovery_threshold":2,
        "cooldown_seconds":60,"max_backoff_seconds":300}}}]}
    """.trimIndent()
