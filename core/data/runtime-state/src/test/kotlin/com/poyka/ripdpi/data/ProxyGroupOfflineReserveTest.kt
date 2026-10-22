package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyGroupOfflineReserveTest {
    @Test
    fun `fresh repository restores complete reserve credentials and payload policy`() =
        runTest {
            val blobStore = FakeProxyGroupBlobStore()
            val accepted = reserveGroup()
            createRepository(blobStore).add(accepted)

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
            val accepted = reserveGroup()
            repository.add(accepted)
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
            val accepted = reserveGroup()
            repository.add(accepted)
            val replacement = accepted.copy(members = accepted.members.takeLast(1))

            repository.update(replacement)

            assertEquals(listOf(replacement), createRepository(blobStore).list())
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
                            schemaVersion = 1,
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
