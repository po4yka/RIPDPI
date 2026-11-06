package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiContractJson
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

class SubscriptionHealthPolicyJournalMigrationTest : RoomProfileTest() {
    @Test
    fun `health decoding preserves provenance ownership and unrelated raw fields without rewriting source`() =
        runTest {
            val fixture = LegacyJournalDecoderFixture()
            val pending = fixture.seed(LegacyPayload)
            val recovered = checkNotNull(fixture.reader.read())
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
            assertEquals(recovered, fixture.reader.read())
            assertEquals(pending, fixture.journal.pending())
        }

    @Test
    fun `failed Room import preserves legacy health bytes and retry accepts a quality refresh`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.seedPayload(ProfileMutationFamily.Subscription, LegacyPayload)
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                legacy.fixture.failWrite("proxy_groups")
                assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                assertEquals(source, legacy.legacyBytes())
                assertEquals(before, legacy.fixture.snapshot())
                legacy.fixture.clearFaults()
                legacy.fixture.initialize()
                val current =
                    legacy.fixture.groupCatalog
                        .snapshot()
                        .single()
                assertEquals("captured-owner", current.ownershipGeneration)
                assertEquals(2, current.failover!!.payloadPolicy!!.schemaVersion)
                val quality = ProfileQualityPolicy(131_076, 262_152, 300, 20, 100)
                val next =
                    current.copy(
                        failover =
                            current.failover!!.copy(
                                payloadPolicy = current.failover!!.payloadPolicy!!.copy(quality = quality),
                            ),
                    )
                assertTrue(
                    legacy.fixture.coordinator().commitSubscriptionRefresh(current) {
                        PreparedSubscriptionRefresh(next, emptyList())
                    },
                )
                val stored =
                    legacy.fixture.groupCatalog
                        .snapshot()
                        .single()
                assertEquals(quality, stored.failover!!.payloadPolicy!!.quality)
                assertNotEquals(current.ownershipGeneration, stored.ownershipGeneration)
                legacy.fixture.reopen()
                assertEquals(
                    stored,
                    legacy.fixture.groupCatalog
                        .snapshot()
                        .single(),
                )
            }
        }

    @Test
    fun `ownership and health decode together without dropping unknown raw fields`() =
        runTest {
            val fixture = LegacyJournalDecoderFixture()
            val pending = fixture.seed(LegacyPayload.replace("\"ownershipVersion\":1", "\"ownershipVersion\":0"))
            val accepted = checkNotNull(fixture.reader.read())
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
            assertEquals(pending, fixture.journal.pending())
        }

    @Test
    fun `invalid legacy policy or ownership never changes source or imports partial rows`() =
        runTest {
            val invalid =
                listOf(
                    LegacyPayload.replace("32768", "65537"),
                    LegacyPayload.replace("\"schema_version\":1", "\"schema_version\":1,\"quality\":null"),
                    LegacyPayload.replace("\"schema_version\":1", "\"schema_version\":1,\"unknown_policy\":true"),
                    LegacyPayload.replace("\"ownershipVersion\":1", "\"ownershipVersion\":2"),
                    LegacyPayload.replace("captured-owner", " "),
                    LegacyPayload.replace("\"awgProfiles\":[]", "\"awgProfiles\":[{}]"),
                )
            for (payload in invalid) {
                LegacyProfileImportFixture().use { legacy ->
                    legacy.seedPayload(ProfileMutationFamily.Subscription, payload)
                    val source = legacy.legacyBytes()
                    val before = legacy.fixture.snapshot()
                    assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                    assertEquals(source, legacy.legacyBytes())
                    assertEquals(before, legacy.fixture.snapshot())
                }
            }
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
