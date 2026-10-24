package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import com.poyka.ripdpi.serialization.RipDpiStrictJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the schema read boundary used by KeystoreProxyGroupBlobStore, including checked writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyGroupStoredPolicyMigrationTest {
    @Test
    fun `production read upgrades both stored wrappers before typed catalogue decoding`() =
        runTest {
            for (version in listOf(1, 2)) {
                val raw = legacyCatalogue(version)
                val storage = SchemaReadBlob(raw, version)
                val accepted = ProxyGroupCatalog(storage).load()
                assertEquals(2, accepted.size)
                assertEquals(expectedGroups().map { it.members }, accepted.map { it.members })
                assertEquals(expectedGroups().map { it.subscription }, accepted.map { it.subscription })
                assertEquals(
                    2,
                    accepted
                        .first()
                        .failover!!
                        .payloadPolicy!!
                        .schemaVersion,
                )
                assertNull(
                    accepted
                        .first()
                        .failover!!
                        .payloadPolicy!!
                        .quality,
                )
                if (version == 2) {
                    assertEquals(expectedGroups(), accepted)
                } else {
                    assertTrue(accepted.all { it.ownershipGeneration.isNotBlank() })
                    assertNotEquals(expectedGroups().first().ownershipGeneration, accepted.first().ownershipGeneration)
                }
                val expectedJson =
                    JsonArray(
                        RipDpiJson
                            .parseToJsonElement(raw.replace("\"schema_version\":1", "\"schema_version\":2"))
                            .jsonArray
                            .zip(accepted) { element, group ->
                                JsonObject(
                                    element.jsonObject +
                                        ("ownershipGeneration" to JsonPrimitive(group.ownershipGeneration)),
                                )
                            },
                    )
                assertEquals(expectedJson, RipDpiJson.parseToJsonElement(storage.raw))
                assertEquals(2, storage.version)
                assertEquals(1, storage.writes)
                assertEquals(accepted, ProxyGroupCatalog(storage).load())
                assertEquals(1, storage.writes)
            }
        }

    @Test
    fun `checked migration failure retains every original byte and publishes no catalogue`() =
        runTest {
            for (version in listOf(1, 2)) {
                val raw = legacyCatalogue(version)
                val storage = SchemaReadBlob(raw, version).apply { failWrites = true }
                val catalogue = ProxyGroupCatalog(storage)
                val observed = mutableListOf<List<ProxyGroup>>()
                val collector =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        catalogue.groups().collect { observed += it }
                    }
                val error = runCatching { catalogue.load() }.exceptionOrNull()
                assertEquals("fixture checked write failure", error?.message)
                assertEquals(raw, storage.raw)
                assertEquals(version, storage.version)
                assertTrue(observed.isEmpty())

                storage.failWrites = false
                val accepted = catalogue.load()
                assertEquals(listOf(accepted), observed)
                assertEquals(accepted, ProxyGroupCatalog(storage).load())
                collector.cancel()
            }
        }

    @Test
    fun `upgraded catalogue accepts a quality update and retains other profiles after restart`() =
        runTest {
            val storage = SchemaReadBlob(legacyCatalogue(), 2)
            val catalogue = ProxyGroupCatalog(storage)
            val accepted = catalogue.load()
            val original = accepted.first()
            val failover = requireNotNull(original.failover)
            val quality = ProfileQualityPolicy(131_076, 262_152, 300, 20, 100)
            val replacement =
                original.copy(
                    name = "Updated subscription",
                    failover = failover.copy(payloadPolicy = failover.payloadPolicy!!.copy(quality = quality)),
                )
            val updated = catalogue.updateGroup(original.id) { replacement }
            val restarted = ProxyGroupCatalog(storage).load()
            assertEquals(listOf(updated, accepted.last()), restarted)
            assertEquals(
                quality,
                restarted
                    .first()
                    .failover!!
                    .payloadPolicy!!
                    .quality,
            )
            assertEquals(original.members, restarted.first().members)
            assertEquals(original.subscription, restarted.first().subscription)
            assertNotEquals(original.ownershipGeneration, restarted.first().ownershipGeneration)
        }

    @Test
    fun `whole catalogue validation precedes any write and wire version one remains rejected`() {
        for (version in listOf(1, 2)) {
            val invalid = legacyCatalogue(version).replace("\"order\":1", "\"order\":\"invalid\"")
            val storage = SchemaReadBlob(invalid, version)
            assertThrows(IllegalArgumentException::class.java) { storage.read() }
            assertEquals(invalid, storage.raw)
            assertEquals(version, storage.version)
            assertEquals(0, storage.writes)
        }
        val policy = requireNotNull(expectedGroups().first().failover?.payloadPolicy)
        val wire =
            RipDpiJson
                .encodeToString(PayloadFailoverPolicy.serializer(), policy)
                .replace("\"schema_version\":2", "\"schema_version\":1")
        assertThrows(IllegalArgumentException::class.java) {
            RipDpiStrictJson.decodeFromString(PayloadFailoverPolicy.serializer(), wire)
        }
    }

    private fun legacyCatalogue(version: Int = 2): String {
        val raw = RipDpiJson.encodeToString(ListSerializer(ProxyGroup.serializer()), expectedGroups())
        val groups = RipDpiJson.parseToJsonElement(raw).jsonArray
        return JsonArray(
            groups.map {
                val rawGroup = if (version == 1) it.jsonObject - "ownershipGeneration" else it.jsonObject
                JsonObject(rawGroup + ("futureField" to JsonPrimitive("retain")))
            },
        ).toString()
            .replace("\"schema_version\":2", "\"schema_version\":1")
    }

    private fun expectedGroups(): List<ProxyGroup> =
        listOf(
            ProxyGroup(
                id = "subscription",
                name = "Subscription",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                ownershipGeneration = "accepted-owner",
                isSelector = true,
                subscription = Subscription(link = "https://subscription.example/config", token = "fixture-token"),
                members =
                    listOf("primary", "reserve").map { id ->
                        ProxyProfile.Trojan(
                            id = id,
                            displayName = id,
                            groupId = "subscription",
                            server = "$id.example",
                            serverPort = 443,
                            password = "fixture-$id-password",
                        )
                    },
                failover =
                    SelectorFailover(
                        probeUrl = "https://first.example/control",
                        intervalSeconds = 60,
                        toleranceMs = 20,
                        payloadPolicy =
                            PayloadFailoverPolicy(
                                2,
                                "select",
                                listOf("first", "second").map {
                                    PayloadProbeControl("https://$it.example/payload", 32_768, "a".repeat(64))
                                },
                                60,
                                10,
                                2,
                                2,
                                60,
                                300,
                            ),
                    ),
            ),
            ProxyGroup(
                id = "local",
                name = "Local",
                type = ProxyGroupType.BASIC,
                order = 1,
                isSelector = false,
                ownershipGeneration = "unrelated-owner",
            ),
        )
}

/** The production sealed store calls this exact read boundary after decryption. */
private class SchemaReadBlob(
    var raw: String,
    var version: Int,
) : ProxyGroupBlobStore {
    var failWrites = false
    var writes = 0

    override fun read(): String = ProxyGroupSchema.read(raw, version, ::write)

    override fun write(json: String) {
        check(!failWrites) { "fixture checked write failure" }
        raw = json
        version = ProxyGroupSchema.SCHEMA_VERSION
        writes++
    }

    override fun clear() {
        raw = "[]"
    }
}
