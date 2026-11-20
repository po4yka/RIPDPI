package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.subscription.BootstrapConsumer
import com.poyka.ripdpi.proxyimport.FakeProxyGroupRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PendingBootstrapProvisionerTest {
    private val consumedAt = 1_700_000_000_000L

    private suspend fun repositoryWithTokenSlot(url: String) =
        FakeProxyGroupRepository().apply {
            add(
                ProxyGroup(
                    "slot",
                    "Preset relay",
                    ProxyGroupType.SUBSCRIPTION,
                    0,
                    false,
                    subscription = Subscription(link = url, kind = SubscriptionKind.BOOTSTRAP),
                ),
            )
        }

    private fun provisioner(repository: FakeProxyGroupRepository) =
        PendingBootstrapProvisioner(
            repository,
            BootstrapConsumer(clockMillis = { consumedAt }),
        ) { expected, _, prepare ->
            if (expected !in repository.list()) {
                false
            } else {
                repository.update(prepare(emptyList()))
                true
            }
        }

    @Test
    fun `a valid token stores delivered credentials once and is consumed`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("trojan://fixture-secret@relay.example:443#relay").build())
                server.start()
                val repository = repositoryWithTokenSlot(server.url("/bootstrap/fixture").toString())
                val provisioner = provisioner(repository)

                assertEquals(listOf(BootstrapProvisionOutcome.Provisioned("slot", 1)), provisioner.provisionPending())
                assertEquals(emptyList<BootstrapProvisionOutcome>(), provisioner.provisionPending())

                val saved = repository.list().single()
                val member = saved.members.single() as ProxyProfile.Trojan
                assertEquals("fixture-secret", member.password)
                assertEquals(consumedAt, saved.subscription?.consumedAt)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `a delivered token cannot provision a replaced slot`() =
        runTest {
            MockWebServer().use { server ->
                val requested = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            requested.complete(Unit)
                            check(release.await(5, TimeUnit.SECONDS))
                            return MockResponse
                                .Builder()
                                .body(
                                    "trojan://fixture-secret@relay.example:443#relay",
                                ).build()
                        }
                    }
                server.start()
                val repository = repositoryWithTokenSlot(server.url("/bootstrap/fixture").toString())
                val replacement = repository.list().single().copy(ownershipGeneration = "replacement")
                val pending = async { provisioner(repository).provisionPending() }

                try {
                    requested.await()
                    repository.update(replacement)
                } finally {
                    release.countDown()
                }

                assertEquals(
                    listOf(BootstrapProvisionOutcome.Failed("slot", BootstrapProvisionFailure.SUPERSEDED)),
                    pending.await(),
                )
                assertEquals(listOf(replacement), repository.list())
            }
        }

    @Test
    fun `a spent token stores no credential and records a typed terminal failure`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(410).build())
                server.start()
                val repository = repositoryWithTokenSlot(server.url("/bootstrap/spent").toString())
                val provisioner = provisioner(repository)

                assertEquals(
                    listOf(BootstrapProvisionOutcome.Failed("slot", BootstrapProvisionFailure.ALREADY_CONSUMED)),
                    provisioner.provisionPending(),
                )
                assertEquals(emptyList<BootstrapProvisionOutcome>(), provisioner.provisionPending())

                val saved = repository.list().single()
                assertTrue(saved.members.isEmpty())
                assertEquals(SubscriptionRefreshFailure.INVALIDATED, saved.subscription?.lastRefreshFailure)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `an unreachable endpoint stores nothing and keeps the token pending`() =
        runTest {
            val server = MockWebServer()
            server.start()
            val url = server.url("/bootstrap/offline").toString()
            server.close()
            val repository = repositoryWithTokenSlot(url)

            assertEquals(
                listOf(BootstrapProvisionOutcome.Failed("slot", BootstrapProvisionFailure.NETWORK_ERROR)),
                provisioner(repository).provisionPending(),
            )

            val saved = repository.list().single()
            assertTrue(saved.members.isEmpty())
            assertNull(saved.subscription?.consumedAt)
        }
}
