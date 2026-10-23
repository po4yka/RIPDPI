package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyGroupOfflineReserveTest {
    @Test
    fun `fresh repository restores complete reserve credentials and payload policy`() =
        runTest {
            val blobStore = FakeProxyGroupBlobStore()
            val repository = createRepository(blobStore)
            repository.add(reserveGroup())
            val accepted = repository.list().single()

            val restarted = createRepository(blobStore)

            assertEquals(listOf(accepted), restarted.list())
            assertEquals(listOf(accepted), restarted.groups().first())
        }

    @Test
    fun `failed replacement does not publish or lose accepted reserves`() =
        runTest {
            val durable = FakeProxyGroupBlobStore()
            var rejectWrites = false
            val storage =
                object : ProxyGroupBlobStore by durable {
                    override fun write(json: String) {
                        check(!rejectWrites) { "fixture-write-failed" }
                        durable.write(json)
                    }
                }
            val repository = createRepository(storage)
            repository.add(reserveGroup())
            val accepted = repository.list().single()
            val acceptedBytes = durable.read()
            rejectWrites = true

            val failure = runCatching { repository.update(accepted.copy(members = emptyList(), failover = null)) }

            assertTrue(failure.exceptionOrNull() is IllegalStateException)
            assertEquals(acceptedBytes, durable.read())
            assertEquals(listOf(accepted), repository.groups().first())
            assertEquals(listOf(accepted), createRepository(durable).list())
        }

    @Test
    fun `successful replacement does not resurrect removed reserves after restart`() =
        runTest {
            val blobStore = FakeProxyGroupBlobStore()
            val repository = createRepository(blobStore)
            repository.add(reserveGroup())
            val accepted = repository.list().single()
            val replacement = accepted.copy(members = accepted.members.takeLast(1))

            repository.update(replacement)

            val stored = repository.list().single()
            assertNotEquals(accepted.ownershipGeneration, stored.ownershipGeneration)
            assertEquals(replacement.copy(ownershipGeneration = stored.ownershipGeneration), stored)
            assertEquals(listOf(stored), createRepository(blobStore).list())
        }

    @Test
    fun `stored v1 health policy upgrades without losing reserves and accepts v2 refresh`() =
        runTest {
            val accepted = reserveGroup().copy(ownershipGeneration = "accepted-owner")
            val legacy =
                RipDpiJson
                    .encodeToString(ListSerializer(ProxyGroup.serializer()), listOf(accepted))
                    .replace("\"schema_version\":2", "\"schema_version\":1")
            val storage = FakeProxyGroupBlobStore(legacy)
            val catalog = ProxyGroupCatalog(storage)

            assertEquals(listOf(accepted), catalog.load())
            assertTrue(storage.read()!!.contains("\"schema_version\":2"))
            assertEquals(listOf(accepted), ProxyGroupCatalog(storage).load())
            val refreshed = accepted.copy(name = "Refreshed v2", members = accepted.members.reversed())
            catalog.updateGroup(accepted.id) { refreshed }
            val restored = ProxyGroupCatalog(storage).load().single()
            assertEquals(refreshed.members, restored.members)
            assertEquals(refreshed.failover, restored.failover)
            assertNotEquals(accepted.ownershipGeneration, restored.ownershipGeneration)
        }

    @Test
    fun `failed local migration preserves accepted bytes and succeeds on retry`() =
        runTest {
            val accepted = reserveGroup().copy(ownershipGeneration = "accepted-owner")
            val legacy =
                RipDpiJson
                    .encodeToString(ListSerializer(ProxyGroup.serializer()), listOf(accepted))
                    .replace("\"schema_version\":2", "\"schema_version\":1")
            val durable = FakeProxyGroupBlobStore(legacy)
            var fail = true
            val storage =
                object : ProxyGroupBlobStore by durable {
                    override fun write(json: String) {
                        check(!fail) { "fixture-migration-write-failed" }
                        durable.write(json)
                    }
                }
            val catalog = ProxyGroupCatalog(storage)
            assertTrue(runCatching { catalog.load() }.isFailure)
            assertEquals(legacy, durable.read())
            fail = false
            assertEquals(listOf(accepted), catalog.load())
        }

    private fun createRepository(storage: ProxyGroupBlobStore) =
        SharedPreferencesProxyGroupRepository(
            com.poyka.ripdpi.data
                .ProxyGroupCatalog(storage),
            testMutationPreparationSource(testPauseAuthority()),
        )

    private fun reserveGroup(): ProxyGroup =
        ProxyGroup(
            id = "reserves",
            name = "Saved reserves",
            type = ProxyGroupType.SUBSCRIPTION,
            order = 0,
            isSelector = true,
            subscription = Subscription(link = "https://subscription.example/profile", token = "fixture-subscription"),
            members =
                listOf("primary", "reserve").map { id ->
                    ProxyProfile.Trojan(
                        id = id,
                        displayName = id,
                        groupId = "reserves",
                        server = "$id.example",
                        serverPort = 443,
                        password = "fixture-$id-credential",
                        serverName = "$id.example",
                    )
                },
            failover =
                SelectorFailover(
                    probeUrl = "https://control.example/payload",
                    intervalSeconds = 30,
                    toleranceMs = 50,
                    payloadPolicy =
                        PayloadFailoverPolicy(
                            schemaVersion = 2,
                            selectorTag = "select",
                            probes =
                                listOf("control", "independent").map {
                                    PayloadProbeControl("https://$it.example/payload", 32_768, "a".repeat(64))
                                },
                            intervalSeconds = 30,
                            timeoutSeconds = 5,
                            failureThreshold = 2,
                            recoveryThreshold = 2,
                            cooldownSeconds = 60,
                            maxBackoffSeconds = 120,
                        ),
                ),
        )
}
